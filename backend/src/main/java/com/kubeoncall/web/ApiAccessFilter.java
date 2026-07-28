package com.kubeoncall.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Protects only the legacy application API with a small role hierarchy while leaving the v1
 * session/API-token surface and signed integration webhooks on their own authentication paths.
 */
@Component
public class ApiAccessFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> VIEWER_POST_PATHS = List.of(
            "/api/ask",
            "/api/knowledge/query",
            "/api/memory/search",
            "/api/alarm-policies/dry-run",
            "/api/alarm-policies/prometheus-rules/dry-run");
    private static final List<String> OPERATOR_PREFIXES =
            List.of("/api/approvals/", "/api/alarms", "/api/change-events", "/api/memory/extractions");

    private final KubeOnCallProperties properties;

    public ApiAccessFilter(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getApiSecurity().isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || path == null
                || !path.startsWith("/api/")
                || path.startsWith("/api/v1/")
                || path.equals("/api/integrations/alertmanager/webhook")
                || path.startsWith("/api/integrations/change-events/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Role required = requiredRole(request);
        Role granted = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (granted == null) {
            writeError(
                    response,
                    configured() ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    configured() ? "API_AUTHENTICATION_REQUIRED" : "API_AUTHENTICATION_UNAVAILABLE");
            return;
        }
        if (granted.ordinal() < required.ordinal()) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "API_ROLE_INSUFFICIENT");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Role requiredRole(HttpServletRequest request) {
        if (HttpMethod.GET.matches(request.getMethod())) {
            return Role.VIEWER;
        }
        String path = request.getRequestURI();
        if (VIEWER_POST_PATHS.contains(path)) {
            return Role.VIEWER;
        }
        if (OPERATOR_PREFIXES.stream().anyMatch(path::startsWith)) {
            return Role.OPERATOR;
        }
        return Role.ADMIN;
    }

    private Role authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String supplied = authorization.substring(BEARER_PREFIX.length()).trim();
        if (supplied.isBlank()) {
            return null;
        }
        if (matches(properties.getApiSecurity().getAdminToken(), supplied)) {
            return Role.ADMIN;
        }
        if (matches(properties.getApiSecurity().getOperatorToken(), supplied)) {
            return Role.OPERATOR;
        }
        if (matches(properties.getApiSecurity().getViewerToken(), supplied)) {
            return Role.VIEWER;
        }
        return null;
    }

    private boolean configured() {
        return nonBlank(properties.getApiSecurity().getAdminToken())
                || nonBlank(properties.getApiSecurity().getOperatorToken())
                || nonBlank(properties.getApiSecurity().getViewerToken());
    }

    private boolean matches(String expected, String actual) {
        if (!nonBlank(expected)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"API access denied\"}");
    }

    private enum Role {
        VIEWER,
        OPERATOR,
        ADMIN
    }
}
