package com.kubeoncall.web.api.v1;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.ApiTokenAuthenticationService;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.BuiltInRole;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.observability.CorrelationContext;

/**
 * Resolves the {@code /api/v1} authenticated principal for each request. Tries, in order: the
 * {@code KOC_SESSION} cookie (browser domain), a persisted scoped API token, then a legacy static
 * role token (compatibility shim during the deprecation window). The result is stored as a
 * {@link V1AuthenticationToken} in the {@link SecurityContextHolder}; unauthenticated requests
 * still proceed as anonymous and rely on route-level authorization to reject them.
 */
@Component
public class V1AuthenticationFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final KubeOnCallProperties properties;
    private final boolean legacyTokenCompat;
    private final ApiTokenAuthenticationService apiTokenAuthenticationService;

    @Autowired
    public V1AuthenticationFilter(
            AuthService authService,
            KubeOnCallProperties properties,
            @Value("${kubeoncall.auth.legacy-token-compat:true}") boolean legacyTokenCompat,
            ApiTokenAuthenticationService apiTokenAuthenticationService) {
        this.authService = authService;
        this.properties = properties;
        this.legacyTokenCompat = legacyTokenCompat;
        this.apiTokenAuthenticationService = apiTokenAuthenticationService;
    }

    /** Compatibility constructor for focused filter tests that do not bootstrap MySQL identity. */
    public V1AuthenticationFilter(AuthService authService, KubeOnCallProperties properties, boolean legacyTokenCompat) {
        this(authService, properties, legacyTokenCompat, null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ResolvedPrincipal resolved = resolveSession(request).orElse(null);
        V1Principal principal;
        if (resolved != null) {
            principal = resolved.principal;
        } else {
            principal = resolveApiToken(request)
                    .or(() -> resolveLegacyToken(request))
                    .orElse(V1Principal.anonymous());
        }
        V1AuthenticationToken authentication = new V1AuthenticationToken(principal);
        if (resolved != null && resolved.csrfSecret != null) {
            // Carry the session-bound CSRF secret on the authentication details so the CSRF filter
            // can validate without a second Redis lookup.
            authentication.setDetails(resolved.csrfSecret);
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        CorrelationContext.put(
                CorrelationContext.USER_ID,
                principal.user() == null ? "anonymous" : principal.user().publicId());
        CorrelationContext.put(
                CorrelationContext.AUTH_METHOD, principal.authMethod().name());
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.remove(CorrelationContext.USER_ID, CorrelationContext.AUTH_METHOD);
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<ResolvedPrincipal> resolveSession(HttpServletRequest request) {
        String sessionId = extractCookie(request, properties.getAuth().getSessionCookieName());
        if (sessionId == null) {
            return Optional.empty();
        }
        return authService
                .resolveSession(sessionId)
                .map(session -> new ResolvedPrincipal(
                        new V1Principal(session.user(), session.user().permissions(), V1Principal.AuthMethod.SESSION),
                        session.session().csrfSecret()));
    }

    private record ResolvedPrincipal(V1Principal principal, String csrfSecret) {}

    private Optional<V1Principal> resolveApiToken(HttpServletRequest request) {
        if (apiTokenAuthenticationService == null) {
            return Optional.empty();
        }
        String token = bearerToken(request);
        if (token == null || !token.startsWith("koc_")) {
            return Optional.empty();
        }
        return apiTokenAuthenticationService.authenticate(token, request.getRemoteAddr());
    }

    private Optional<V1Principal> resolveLegacyToken(HttpServletRequest request) {
        if (!legacyTokenCompat) {
            return Optional.empty();
        }
        String token = bearerToken(request);
        if (token == null || token.startsWith("koc_")) {
            return Optional.empty();
        }
        KubeOnCallProperties.ApiSecurity apiSecurity = properties.getApiSecurity();
        if (matches(apiSecurity.getAdminToken(), token)) {
            return Optional.of(legacyPrincipal(BuiltInRole.ADMIN));
        }
        if (matches(apiSecurity.getOperatorToken(), token)) {
            return Optional.of(legacyPrincipal(BuiltInRole.OPERATOR));
        }
        if (matches(apiSecurity.getViewerToken(), token)) {
            return Optional.of(legacyPrincipal(BuiltInRole.VIEWER));
        }
        return Optional.empty();
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    /** Legacy tokens map to a synthetic user with the full permission set of the matched role. */
    private V1Principal legacyPrincipal(BuiltInRole role) {
        UserAccount synthetic = new UserAccount(
                0,
                "legacy_" + role.name().toLowerCase(),
                role.name().toLowerCase(),
                role.name(),
                null,
                "",
                "LEGACY",
                1,
                "ACTIVE",
                1,
                null,
                null,
                null,
                0,
                java.util.Set.of(role.name()),
                legacyPermissions(role));
        return new V1Principal(synthetic, synthetic.permissions(), V1Principal.AuthMethod.LEGACY_TOKEN);
    }

    private java.util.Set<String> legacyPermissions(BuiltInRole role) {
        return switch (role) {
            case VIEWER ->
                java.util.Set.of(
                        PermissionCode.DASHBOARD_READ,
                        PermissionCode.ALARM_READ,
                        PermissionCode.EXECUTION_READ,
                        PermissionCode.APPROVAL_READ,
                        PermissionCode.ASK_EXECUTE,
                        PermissionCode.KNOWLEDGE_READ,
                        PermissionCode.MEMORY_READ,
                        PermissionCode.SKILL_READ,
                        PermissionCode.TOOL_READ,
                        PermissionCode.POLICY_READ,
                        PermissionCode.MAINTENANCE_READ,
                        PermissionCode.CHANGE_READ,
                        PermissionCode.INTEGRATION_READ,
                        PermissionCode.USER_READ,
                        PermissionCode.TOKEN_READ_OWN,
                        PermissionCode.AUDIT_READ,
                        PermissionCode.SYSTEM_READ,
                        PermissionCode.SANDBOX_READ);
            case OPERATOR ->
                java.util.Set.of(
                        PermissionCode.DASHBOARD_READ,
                        PermissionCode.ALARM_READ,
                        PermissionCode.ALARM_ACKNOWLEDGE,
                        PermissionCode.ALARM_RECOVER,
                        PermissionCode.ALARM_SILENCE,
                        PermissionCode.EXECUTION_READ,
                        PermissionCode.EXECUTION_CREATE,
                        PermissionCode.EXECUTION_CANCEL,
                        PermissionCode.EXECUTION_RETRY,
                        PermissionCode.APPROVAL_READ,
                        PermissionCode.APPROVAL_DECIDE,
                        PermissionCode.ASK_EXECUTE,
                        PermissionCode.KNOWLEDGE_READ,
                        PermissionCode.MEMORY_READ,
                        PermissionCode.SKILL_READ,
                        PermissionCode.TOOL_READ,
                        PermissionCode.POLICY_READ,
                        PermissionCode.MAINTENANCE_READ,
                        PermissionCode.MAINTENANCE_MANAGE,
                        PermissionCode.CHANGE_READ,
                        PermissionCode.INTEGRATION_READ,
                        PermissionCode.USER_READ,
                        PermissionCode.TOKEN_READ_OWN,
                        PermissionCode.TOKEN_MANAGE_OWN,
                        PermissionCode.AUDIT_READ,
                        PermissionCode.SYSTEM_READ,
                        PermissionCode.SANDBOX_READ,
                        PermissionCode.SANDBOX_EXECUTE,
                        PermissionCode.SANDBOX_CANCEL);
            case ADMIN ->
                java.util.Set.of(
                        PermissionCode.DASHBOARD_READ,
                        PermissionCode.ALARM_READ,
                        PermissionCode.ALARM_ACKNOWLEDGE,
                        PermissionCode.ALARM_RECOVER,
                        PermissionCode.ALARM_SILENCE,
                        PermissionCode.EXECUTION_READ,
                        PermissionCode.EXECUTION_CREATE,
                        PermissionCode.EXECUTION_CANCEL,
                        PermissionCode.EXECUTION_RETRY,
                        PermissionCode.APPROVAL_READ,
                        PermissionCode.APPROVAL_DECIDE,
                        PermissionCode.ASK_EXECUTE,
                        PermissionCode.KNOWLEDGE_READ,
                        PermissionCode.KNOWLEDGE_WRITE,
                        PermissionCode.KNOWLEDGE_DELETE,
                        PermissionCode.KNOWLEDGE_INDEX_MANAGE,
                        PermissionCode.MEMORY_READ,
                        PermissionCode.MEMORY_WRITE,
                        PermissionCode.MEMORY_MAINTAIN,
                        PermissionCode.SKILL_READ,
                        PermissionCode.SKILL_MANAGE,
                        PermissionCode.TOOL_READ,
                        PermissionCode.POLICY_READ,
                        PermissionCode.POLICY_MANAGE,
                        PermissionCode.MAINTENANCE_READ,
                        PermissionCode.MAINTENANCE_MANAGE,
                        PermissionCode.CHANGE_READ,
                        PermissionCode.CHANGE_WRITE,
                        PermissionCode.INTEGRATION_READ,
                        PermissionCode.INTEGRATION_MANAGE,
                        PermissionCode.USER_READ,
                        PermissionCode.USER_MANAGE,
                        PermissionCode.TOKEN_READ_OWN,
                        PermissionCode.TOKEN_MANAGE_OWN,
                        PermissionCode.TOKEN_MANAGE_ALL,
                        PermissionCode.AUDIT_READ,
                        PermissionCode.AUDIT_EXPORT,
                        PermissionCode.SYSTEM_READ,
                        PermissionCode.SYSTEM_MANAGE,
                        PermissionCode.SANDBOX_READ,
                        PermissionCode.SANDBOX_EXECUTE,
                        PermissionCode.SANDBOX_CANCEL,
                        PermissionCode.SANDBOX_MANAGE);
        };
    }

    private static boolean matches(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
