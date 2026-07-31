package com.kubeoncall.tool.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
@Primary
@ConditionalOnProperty(prefix = "kubeoncall.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpMcpClient implements McpClient {

    private static final String JSON_RPC = "2.0";

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;
    private final McpToolPolicy toolPolicy;
    private final McpOAuthTokenProvider tokenProvider;
    private final AtomicLong requestIds = new AtomicLong();
    private volatile boolean initialized;
    private volatile String initializedEndpoint;
    private volatile String sessionId;
    private volatile String negotiatedProtocolVersion;

    public HttpMcpClient(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this(toolHttpClient, properties, new McpOAuthTokenProvider(null, null, properties));
    }

    @Autowired
    public HttpMcpClient(
            ToolHttpClient toolHttpClient, KubeOnCallProperties properties, McpOAuthTokenProvider tokenProvider) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
        this.toolPolicy = new McpToolPolicy(properties);
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Map<String, Object> call(String toolName, Map<String, Object> payload) {
        if (!toolPolicy.allowed(toolName)) {
            return failed(toolName, "McpToolNotAllowed", "MCP tool is not allowed by local policy");
        }
        if (usesJsonRpc()) {
            return bounded(invokeJsonRpc(
                    properties.getMcp().getEndpoint(),
                    "tools/call",
                    Map.of("name", toolName, "arguments", payload == null ? Map.of() : payload),
                    toolName));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("serverName", properties.getMcp().getServerName());
        request.put("toolName", toolName);
        request.put("payload", payload == null ? Map.of() : payload);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetSystem", "mcp");
        metadata.put("tool", toolName);

        return bounded(toolHttpClient.post(
                properties.getMcp().getEndpoint(),
                request,
                properties.getMcp().getTimeoutMillis(),
                authorizationHeaders(),
                metadata,
                properties.getMcp().getMaxResponseBytes()));
    }

    @Override
    public List<Map<String, Object>> listTools() {
        if (!properties.getMcp().isDiscoveryEnabled()) {
            return List.of();
        }
        if (usesJsonRpc()) {
            String endpoint = firstNonBlank(
                    properties.getMcp().getDiscoveryEndpoint(),
                    properties.getMcp().getEndpoint());
            return listJsonRpcTools(endpoint);
        }
        if (properties.getMcp().getDiscoveryEndpoint() == null
                || properties.getMcp().getDiscoveryEndpoint().isBlank()) {
            return List.of();
        }
        Map<String, Object> response = toolHttpClient.post(
                properties.getMcp().getDiscoveryEndpoint(),
                Map.of("serverName", properties.getMcp().getServerName(), "method", "tools/list"),
                properties.getMcp().getTimeoutMillis(),
                authorizationHeaders(),
                Map.of("targetSystem", "mcp", "tool", "tools/list"),
                properties.getMcp().getMaxResponseBytes());
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            throw discoveryFailure(response);
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> map) || !(map.get("tools") instanceof List<?> tools)) {
            throw new IllegalStateException("MCP discovery response is missing tools");
        }
        return tools.stream()
                .filter(Map.class::isInstance)
                .map(this::toStringMap)
                .toList();
    }

    private Map<String, Object> invokeJsonRpc(
            String endpoint, String method, Map<String, Object> params, String toolName) {
        if (!ensureInitialized(endpoint)) {
            return failed(toolName, "McpInitializationError", "MCP initialize failed");
        }
        Map<String, Object> rpcRequest = request(method, params, true);
        Object expectedId = rpcRequest.get("id");
        Map<String, Object> transportResponse = postJsonRpc(endpoint, rpcRequest, toolName);
        if (sessionExpired(transportResponse)) {
            resetSession();
            if (ensureInitialized(endpoint)) {
                rpcRequest = request(method, params, true);
                expectedId = rpcRequest.get("id");
                transportResponse = postJsonRpc(endpoint, rpcRequest, toolName);
            }
        }
        if (authenticationExpired(transportResponse)) {
            tokenProvider.acceptChallenge(transportResponse);
            tokenProvider.invalidate();
            resetSession();
            if (ensureInitialized(endpoint)) {
                rpcRequest = request(method, params, true);
                expectedId = rpcRequest.get("id");
                transportResponse = postJsonRpc(endpoint, rpcRequest, toolName);
            }
        }
        return normalizeJsonRpc(transportResponse, toolName, expectedId);
    }

    private synchronized boolean ensureInitialized(String endpoint) {
        if (initialized && java.util.Objects.equals(initializedEndpoint, endpoint)) {
            return true;
        }
        resetSession();
        Map<String, Object> params = Map.of(
                "protocolVersion",
                properties.getMcp().getProtocolVersion(),
                "capabilities",
                clientCapabilities(),
                "clientInfo",
                Map.of(
                        "name", properties.getMcp().getClientName(),
                        "version", properties.getMcp().getClientVersion()));
        Map<String, Object> initializeRequest = request("initialize", params, true);
        Map<String, Object> response = postJsonRpc(endpoint, initializeRequest, "initialize");
        Map<String, Object> normalized = normalizeJsonRpc(response, "initialize", initializeRequest.get("id"));
        if (!"success".equalsIgnoreCase(String.valueOf(normalized.get("status")))) {
            return false;
        }
        sessionId = responseHeader(response, "mcp-session-id");
        Object result = normalized.get("response");
        if (result instanceof Map<?, ?> values && values.get("protocolVersion") != null) {
            negotiatedProtocolVersion = String.valueOf(values.get("protocolVersion"));
        }
        initializedEndpoint = endpoint;
        initialized = true;
        postJsonRpc(endpoint, request("notifications/initialized", Map.of(), false), "notifications/initialized");
        return true;
    }

    private Map<String, Object> request(String method, Map<String, Object> params, boolean responseExpected) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", JSON_RPC);
        if (responseExpected) {
            request.put("id", requestIds.incrementAndGet());
        }
        request.put("method", method);
        request.put("params", params == null ? Map.of() : params);
        return request;
    }

    private Map<String, Object> postJsonRpc(String endpoint, Map<String, Object> request, String toolName) {
        return toolHttpClient.post(
                endpoint,
                request,
                properties.getMcp().getTimeoutMillis(),
                jsonRpcHeaders(request),
                Map.of("targetSystem", "mcp", "tool", toolName, "protocol", "jsonrpc"),
                properties.getMcp().getMaxResponseBytes());
    }

    private Map<String, String> jsonRpcHeaders(Map<String, Object> request) {
        Map<String, String> headers = new LinkedHashMap<>(authorizationHeaders());
        headers.put("Accept", "application/json, text/event-stream");
        headers.put(
                "MCP-Protocol-Version",
                firstNonBlank(negotiatedProtocolVersion, properties.getMcp().getProtocolVersion()));
        Object method = request.get("method");
        if (method != null) {
            headers.put("Mcp-Method", String.valueOf(method));
        }
        Object params = request.get("params");
        if (params instanceof Map<?, ?> values && values.get("name") != null) {
            headers.put("Mcp-Name", String.valueOf(values.get("name")));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            headers.put("Mcp-Session-Id", sessionId);
        }
        return Map.copyOf(headers);
    }

    private boolean sessionExpired(Map<String, Object> response) {
        Object status = response.get("httpStatus");
        return status instanceof Number number && number.intValue() == 404;
    }

    private boolean authenticationExpired(Map<String, Object> response) {
        Object status = response.get("httpStatus");
        return status instanceof Number number
                && (number.intValue() == 401 || number.intValue() == 403)
                && tokenProvider.clientCredentialsEnabled();
    }

    private Map<String, Object> clientCapabilities() {
        return tokenProvider.clientCredentialsEnabled()
                ? Map.of("extensions", Map.of("io.modelcontextprotocol/oauth-client-credentials", Map.of()))
                : Map.of();
    }

    private synchronized void resetSession() {
        initialized = false;
        initializedEndpoint = null;
        sessionId = null;
        negotiatedProtocolVersion = null;
    }

    private Map<String, Object> normalizeJsonRpc(
            Map<String, Object> transportResponse, String toolName, Object expectedId) {
        if (!"success".equalsIgnoreCase(String.valueOf(transportResponse.get("status")))) {
            return transportResponse;
        }
        Object body = transportResponse.get("response");
        if (!(body instanceof Map<?, ?> response)) {
            return failed(toolName, "McpProtocolError", "MCP response is not a JSON object");
        }
        if (response.get("error") != null) {
            Map<String, Object> failed = new LinkedHashMap<>(transportResponse);
            failed.put("status", "failed");
            failed.put("errorType", "McpJsonRpcError");
            failed.put("errorMessage", String.valueOf(response.get("error")));
            return Map.copyOf(failed);
        }
        if (expectedId != null && !String.valueOf(expectedId).equals(String.valueOf(response.get("id")))) {
            return failed(toolName, "McpProtocolError", "MCP response id does not match the request");
        }
        if (!JSON_RPC.equals(String.valueOf(response.get("jsonrpc"))) || !response.containsKey("result")) {
            return failed(toolName, "McpProtocolError", "MCP response is missing a JSON-RPC result");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(transportResponse);
        normalized.put("response", response.get("result"));
        return Map.copyOf(normalized);
    }

    private List<Map<String, Object>> listJsonRpcTools(String endpoint) {
        List<Map<String, Object>> collected = new java.util.ArrayList<>();
        String cursor = null;
        int maxPages = Math.max(1, properties.getMcp().getDiscoveryMaxPages());
        for (int page = 0; page < maxPages; page++) {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            Map<String, Object> response = bounded(invokeJsonRpc(endpoint, "tools/list", params, "tools/list"));
            if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
                throw discoveryFailure(response);
            }
            Object body = response.get("response");
            if (!(body instanceof Map<?, ?> result) || !(result.get("tools") instanceof List<?> tools)) {
                throw new IllegalStateException("MCP discovery response is missing tools");
            }
            tools.stream().filter(Map.class::isInstance).map(this::toStringMap).forEach(collected::add);
            Object nextCursor = result.get("nextCursor");
            cursor = nextCursor == null || String.valueOf(nextCursor).isBlank() ? null : String.valueOf(nextCursor);
            if (cursor == null) {
                return List.copyOf(collected);
            }
        }
        throw new IllegalStateException("MCP discovery exceeded the configured page limit");
    }

    private IllegalStateException discoveryFailure(Map<String, Object> response) {
        Object errorType = response == null ? null : response.get("errorType");
        String suffix = errorType == null ? "" : ": " + errorType;
        return new IllegalStateException("MCP discovery failed" + suffix);
    }

    private Map<String, Object> bounded(Map<String, Object> response) {
        return "ResponseTooLarge".equals(response.get("errorType"))
                ? failed("response", "McpResponseTooLarge", "MCP response exceeds the configured byte limit")
                : response;
    }

    private Map<String, Object> failed(String toolName, String errorType, String message) {
        return Map.of(
                "targetSystem",
                "mcp",
                "tool",
                toolName,
                "protocol",
                "jsonrpc",
                "status",
                "failed",
                "errorType",
                errorType,
                "errorMessage",
                message);
    }

    private String responseHeader(Map<String, Object> response, String name) {
        Object headers = response.get("responseHeaders");
        if (!(headers instanceof Map<?, ?> values)) {
            return null;
        }
        Object value = values.get(name.toLowerCase(java.util.Locale.ROOT));
        return value == null ? null : String.valueOf(value);
    }

    private boolean usesJsonRpc() {
        return "jsonrpc".equalsIgnoreCase(properties.getMcp().getProtocol());
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private Map<String, Object> toStringMap(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        Map<String, Object> converted = new LinkedHashMap<>();
        map.forEach((key, value) -> converted.put(String.valueOf(key), value));
        return Map.copyOf(converted);
    }

    private Map<String, String> authorizationHeaders() {
        return tokenProvider
                .authorizationHeader()
                .map(value -> Map.of("Authorization", value))
                .orElseGet(Map::of);
    }
}
