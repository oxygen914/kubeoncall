package com.kubeoncall.web.api.v1;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.identity.AuthService;

/**
 * CSRF guard for cookie-authenticated {@code /api/v1} mutating requests. Only enforced when the
 * request was authenticated via a browser session; API-token and legacy-token callers (which supply
 * an explicit credential and cannot be CSRF'd) are exempt. The login endpoint is exempt because the
 * session does not exist yet; it is instead protected by SameSite, Origin/Referer and login rate
 * limiting. Failures return the unified error envelope with {@code FORBIDDEN}.
 */
public class V1CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public V1CsrfFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())
                || isLoginEndpoint(request)
                || isClientTelemetryEndpoint(request)
                || !isSessionAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        String sessionSecret = currentSessionSecret();
        String supplied = request.getHeader(CSRF_HEADER);
        if (!authService.csrfService().isValid(sessionSecret, supplied)) {
            writeCsrfError(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isLoginEndpoint(HttpServletRequest request) {
        return "/api/v1/auth/login".equals(request.getRequestURI());
    }

    /**
     * Browser unload reporting uses {@code sendBeacon}, which cannot set the CSRF header. This
     * endpoint is safe to exempt because it only accepts a bounded allowlist, performs no business
     * mutation, requires an authenticated principal in the controller and is rate-limited.
     */
    private boolean isClientTelemetryEndpoint(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && "/api/v1/client-events".equals(request.getRequestURI());
    }

    private boolean isSessionAuthenticated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof V1Principal principal)) {
            return false;
        }
        return principal.authMethod() == V1Principal.AuthMethod.SESSION;
    }

    private String currentSessionSecret() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object details = authentication.getDetails();
        return details instanceof String secret ? secret : null;
    }

    private void writeCsrfError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiErrorEnvelope envelope = ApiErrorEnvelope.of(
                "FORBIDDEN",
                "CSRF token missing or invalid",
                false,
                java.util.List.of(),
                java.util.Map.of(),
                RequestIdFilter.currentRequestId());
        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }
}
