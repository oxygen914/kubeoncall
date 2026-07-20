package com.kubeoncall.tool.mcp;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;

/** Machine-to-machine OAuth provider for the MCP client-credentials extension. */
@Component
public class McpOAuthTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthTokenProvider.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_TOKEN_RESPONSE_BYTES = 64 * 1024;
    private static final Pattern RESOURCE_METADATA_PATTERN =
            Pattern.compile("resource_metadata=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCOPE_PATTERN =
            Pattern.compile("(?:^|[,\\s])scope=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private volatile CachedToken cachedToken;
    private volatile String challengeMetadataUrl;
    private volatile String challengedScope;
    private volatile OAuthMetadata discoveredMetadata;

    public McpOAuthTokenProvider(HttpClient httpClient, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<String> authorizationHeader() {
        String mode = properties.getMcp().getAuthMode();
        if (mode == null || mode.isBlank() || "none".equalsIgnoreCase(mode)) {
            return Optional.empty();
        }
        if (!"oauth_client_credentials".equalsIgnoreCase(mode)) {
            String apiKey = properties.getMcp().getApiKey();
            return apiKey == null || apiKey.isBlank() ? Optional.empty() : Optional.of("Bearer " + apiKey.trim());
        }
        return accessToken().map(token -> "Bearer " + token);
    }

    public synchronized void invalidate() {
        cachedToken = null;
    }

    public synchronized void acceptChallenge(Map<String, Object> transportResponse) {
        String challenge = responseHeader(transportResponse, "www-authenticate");
        if (challenge == null || challenge.isBlank()) {
            return;
        }
        Matcher resourceMetadata = RESOURCE_METADATA_PATTERN.matcher(challenge);
        if (resourceMetadata.find()) {
            challengeMetadataUrl = resourceMetadata.group(1);
            discoveredMetadata = null;
        }
        Matcher scope = SCOPE_PATTERN.matcher(challenge);
        if (scope.find()) {
            challengedScope = scope.group(1).trim();
        }
        cachedToken = null;
    }

    public boolean clientCredentialsEnabled() {
        return "oauth_client_credentials".equalsIgnoreCase(properties.getMcp().getAuthMode());
    }

    private synchronized Optional<String> accessToken() {
        int skew = Math.max(5, properties.getMcp().getOauthRefreshSkewSeconds());
        if (cachedToken != null && Instant.now().plusSeconds(skew).isBefore(cachedToken.expiresAt())) {
            return Optional.of(cachedToken.value());
        }
        if (!configured()) {
            log.warn("MCP OAuth client credentials are enabled but incomplete");
            return Optional.empty();
        }
        try {
            OAuthMetadata metadata = oauthMetadata();
            if (metadata == null || metadata.tokenEndpoint() == null) {
                log.warn("MCP OAuth metadata discovery did not yield a token endpoint");
                return Optional.empty();
            }
            URI endpoint = metadata.tokenEndpoint();
            validateEndpoint(endpoint);
            List<String> fields = new ArrayList<>();
            fields.add(field("grant_type", "client_credentials"));
            if (!"client_secret_basic".equalsIgnoreCase(properties.getMcp().getOauthClientAuthMethod())) {
                fields.add(field("client_id", properties.getMcp().getOauthClientId()));
                fields.add(field("client_secret", properties.getMcp().getOauthClientSecret()));
            }
            addOptional(fields, "scope", scope(metadata));
            addOptional(fields, "resource", resource());
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(Math.max(500, properties.getMcp().getTimeoutMillis())))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(String.join("&", fields), StandardCharsets.UTF_8));
            if ("client_secret_basic".equalsIgnoreCase(properties.getMcp().getOauthClientAuthMethod())) {
                String credentials = properties.getMcp().getOauthClientId() + ":"
                        + properties.getMcp().getOauthClientSecret();
                request.header(
                        "Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<InputStream> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                log.warn("MCP OAuth token endpoint rejected client credentials: httpStatus={}", response.statusCode());
                return Optional.empty();
            }
            byte[] responseBytes;
            try (InputStream responseBody = response.body()) {
                responseBytes = responseBody.readNBytes(MAX_TOKEN_RESPONSE_BYTES + 1);
            }
            if (responseBytes.length > MAX_TOKEN_RESPONSE_BYTES) {
                log.warn("MCP OAuth token response exceeds the safe byte limit");
                return Optional.empty();
            }
            Map<String, Object> body = objectMapper.readValue(responseBytes, MAP_TYPE);
            String token = text(body.get("access_token"));
            if (token.isBlank() || !"bearer".equalsIgnoreCase(text(body.getOrDefault("token_type", "Bearer")))) {
                log.warn("MCP OAuth token response is missing a bearer access token");
                return Optional.empty();
            }
            long expiresIn = number(body.get("expires_in"), 300L);
            cachedToken = new CachedToken(token, Instant.now().plusSeconds(Math.max(30, expiresIn)));
            return Optional.of(token);
        } catch (Exception ex) {
            log.warn(
                    "MCP OAuth token acquisition failed: errorType={}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean configured() {
        return httpClient != null
                && objectMapper != null
                && notBlank(properties.getMcp().getOauthClientId())
                && notBlank(properties.getMcp().getOauthClientSecret());
    }

    private synchronized OAuthMetadata oauthMetadata() {
        if (notBlank(properties.getMcp().getOauthTokenEndpoint())) {
            return new OAuthMetadata(URI.create(properties.getMcp().getOauthTokenEndpoint()), null, List.of());
        }
        if (discoveredMetadata != null) {
            return discoveredMetadata;
        }
        for (URI resourceMetadataUri : protectedResourceMetadataCandidates()) {
            Map<String, Object> resourceMetadata = getMetadata(resourceMetadataUri);
            if (resourceMetadata.isEmpty()) {
                continue;
            }
            String resource = text(resourceMetadata.get("resource"));
            List<String> scopes = stringList(resourceMetadata.get("scopes_supported"));
            for (String issuerValue : stringList(resourceMetadata.get("authorization_servers"))) {
                URI issuer = safeHttpsUri(issuerValue);
                if (issuer == null) {
                    continue;
                }
                for (URI candidate : authorizationMetadataCandidates(issuer)) {
                    Map<String, Object> authorizationMetadata = getMetadata(candidate);
                    String advertisedIssuer = text(authorizationMetadata.get("issuer"));
                    if (notBlank(advertisedIssuer) && !sameIssuer(issuer, advertisedIssuer)) {
                        continue;
                    }
                    URI tokenEndpoint = safeHttpsUri(text(authorizationMetadata.get("token_endpoint")));
                    if (tokenEndpoint != null) {
                        discoveredMetadata = new OAuthMetadata(tokenEndpoint, resource, scopes);
                        return discoveredMetadata;
                    }
                }
            }
        }
        return null;
    }

    private List<URI> protectedResourceMetadataCandidates() {
        Set<URI> candidates = new LinkedHashSet<>();
        URI challenged = safeHttpsUri(challengeMetadataUrl);
        if (challenged != null) {
            candidates.add(challenged);
        }
        URI resource = safeHttpsUri(resource());
        if (resource == null) {
            return List.copyOf(candidates);
        }
        String path = resource.getRawPath() == null ? "" : resource.getRawPath();
        String origin = origin(resource);
        if (!path.isBlank() && !"/".equals(path)) {
            candidates.add(URI.create(origin + "/.well-known/oauth-protected-resource" + normalizedPath(path)));
        }
        candidates.add(URI.create(origin + "/.well-known/oauth-protected-resource"));
        return List.copyOf(candidates);
    }

    private List<URI> authorizationMetadataCandidates(URI issuer) {
        String origin = origin(issuer);
        String path = issuer.getRawPath() == null ? "" : issuer.getRawPath();
        if (path.isBlank() || "/".equals(path)) {
            return List.of(
                    URI.create(origin + "/.well-known/oauth-authorization-server"),
                    URI.create(origin + "/.well-known/openid-configuration"));
        }
        String normalized = normalizedPath(path);
        return List.of(
                URI.create(origin + "/.well-known/oauth-authorization-server" + normalized),
                URI.create(origin + "/.well-known/openid-configuration" + normalized),
                URI.create(origin + normalized + "/.well-known/openid-configuration"));
    }

    private Map<String, Object> getMetadata(URI endpoint) {
        try {
            validateEndpoint(endpoint);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(Math.max(500, properties.getMcp().getTimeoutMillis())))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream body = response.body()) {
                responseBytes = body.readNBytes(MAX_TOKEN_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() < 200
                    || response.statusCode() >= 300
                    || responseBytes.length > MAX_TOKEN_RESPONSE_BYTES) {
                return Map.of();
            }
            return objectMapper.readValue(responseBytes, MAP_TYPE);
        } catch (Exception ex) {
            log.debug(
                    "MCP OAuth metadata endpoint is unavailable: endpoint={}, errorType={}",
                    endpoint,
                    ex.getClass().getSimpleName());
            return Map.of();
        }
    }

    private String scope(OAuthMetadata metadata) {
        if (notBlank(challengedScope)) {
            return challengedScope;
        }
        if (notBlank(properties.getMcp().getOauthScope())) {
            return properties.getMcp().getOauthScope();
        }
        return String.join(" ", metadata.scopes());
    }

    private String responseHeader(Map<String, Object> response, String name) {
        Object rawHeaders = response == null ? null : response.get("responseHeaders");
        if (!(rawHeaders instanceof Map<?, ?> headers)) {
            return null;
        }
        Object value = headers.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).filter(this::notBlank).toList();
    }

    private URI safeHttpsUri(String value) {
        if (!notBlank(value)) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            if (uri.getUserInfo() != null || uri.getFragment() != null) {
                return null;
            }
            validateEndpoint(uri);
            return uri;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String origin(URI uri) {
        int port = uri.getPort();
        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        return uri.getScheme() + "://" + host + (port < 0 ? "" : ":" + port);
    }

    private boolean sameIssuer(URI expected, String advertised) {
        URI actual = safeHttpsUri(advertised);
        if (actual == null) {
            return false;
        }
        String expectedValue = expected.toString().replaceAll("/+$", "");
        String actualValue = actual.toString().replaceAll("/+$", "");
        return expectedValue.equals(actualValue);
    }

    private String normalizedPath(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private void validateEndpoint(URI endpoint) {
        String host = endpoint.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !local) {
            throw new IllegalArgumentException("MCP OAuth token endpoint must use HTTPS");
        }
    }

    private String resource() {
        String configured = properties.getMcp().getOauthResource();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (discoveredMetadata != null && notBlank(discoveredMetadata.resource())) {
            return discoveredMetadata.resource();
        }
        return properties.getMcp().getEndpoint();
    }

    private void addOptional(List<String> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(field(name, value.trim()));
        }
    }

    private String field(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record CachedToken(String value, Instant expiresAt) {}

    private record OAuthMetadata(URI tokenEndpoint, String resource, List<String> scopes) {
        private OAuthMetadata {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
