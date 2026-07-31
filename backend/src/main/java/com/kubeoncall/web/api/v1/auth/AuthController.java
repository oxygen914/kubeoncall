package com.kubeoncall.web.api.v1.auth;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;

/**
 * {@code /api/v1/auth} — login, logout and current-session queries for the browser auth domain. On
 * successful login the server sets the {@code KOC_SESSION} cookie (HttpOnly, SameSite=Lax, Secure
 * configurable) and a readable {@code KOC_CSRF} cookie whose value the frontend echoes back as
 * {@code X-CSRF-Token}. The session id and CSRF secret never reach the response body.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final KubeOnCallProperties properties;

    public AuthController(AuthService authService, KubeOnCallProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<SessionView>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        if (!authService.isAvailable()) {
            throw unavailable();
        }
        String requestId = RequestIdFilter.currentRequestId();
        AuthService.LoginResult result = authService.login(
                request.username(),
                request.password().toCharArray(),
                clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT),
                requestId);
        return switch (result.status()) {
            case SUCCESS -> {
                ResponseCookie sessionCookie =
                        buildSessionCookie(result.sessionId(), result.session().expiresAt());
                ResponseCookie csrfCookie = buildCsrfCookie(
                        authService.csrfService().deriveToken(result.session().csrfSecret()));
                response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie.toString());
                response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
                yield ResponseEntity.ok(ApiResponse.ok(toView(result), requestId));
            }
            case INVALID_CREDENTIALS -> throw invalidCredentials();
            case ACCOUNT_LOCKED -> throw accountLocked();
            case UNAVAILABLE -> throw unavailable();
        };
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = extractCookie(request, properties.getAuth().getSessionCookieName());
        authService.logout(sessionId);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                expiredCookie(properties.getAuth().getSessionCookieName()).toString());
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                expiredCookie(properties.getAuth().getCsrfCookieName()).toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    public ApiResponse<SessionView> session() {
        V1Principal principal = currentPrincipal();
        String requestId = RequestIdFilter.currentRequestId();
        if (!principal.isAuthenticated()) {
            // Unauthenticated callers get 401 with the unified envelope; the frontend treats this as
            // "not logged in" and routes to /login rather than treating it as a hard error.
            throw new V1ApiException(401, V1ApiErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
        return ApiResponse.ok(toView(principal), requestId);
    }

    private SessionView toView(AuthService.LoginResult result) {
        UserAccount user = result.user();
        return new SessionView(
                true,
                new UserView(
                        user.publicId(),
                        user.username(),
                        user.displayName(),
                        List.copyOf(user.roles()),
                        List.copyOf(user.permissions())),
                result.session().expiresAt(),
                null);
    }

    private SessionView toView(V1Principal principal) {
        UserAccount user = principal.user();
        return new SessionView(
                true,
                new UserView(
                        user.publicId(),
                        user.username(),
                        user.displayName(),
                        List.copyOf(user.roles()),
                        List.copyOf(user.permissions())),
                null,
                null);
    }

    private ResponseCookie buildSessionCookie(String sessionId, Instant expiresAt) {
        return ResponseCookie.from(properties.getAuth().getSessionCookieName(), sessionId)
                .httpOnly(true)
                .secure(properties.getAuth().isSessionCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.getAuth().getSessionMaxTimeoutSeconds())
                .build();
    }

    private ResponseCookie buildCsrfCookie(String token) {
        // Readable (non-HttpOnly) so the frontend JS can read it and echo it in X-CSRF-Token. It is
        // not a credential; the server validates the echoed value against the session secret.
        return ResponseCookie.from(properties.getAuth().getCsrfCookieName(), token)
                .httpOnly(false)
                .secure(properties.getAuth().isSessionCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.getAuth().getSessionMaxTimeoutSeconds())
                .build();
    }

    private ResponseCookie expiredCookie(String name) {
        return ResponseCookie.from(name, "").httpOnly(true).path("/").maxAge(0).build();
    }

    private V1Principal currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof V1Principal principal) {
            return principal;
        }
        return V1Principal.anonymous();
    }

    private static String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static V1ApiException invalidCredentials() {
        return new V1ApiException(401, V1ApiErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
    }

    private static V1ApiException accountLocked() {
        return new V1ApiException(
                HttpStatus.TOO_MANY_REQUESTS.value(), V1ApiErrorCode.AUTH_ACCOUNT_LOCKED, "Account temporarily locked");
    }

    private static V1ApiException unavailable() {
        return new V1ApiException(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                V1ApiErrorCode.SERVICE_UNAVAILABLE,
                "Authentication is not available");
    }

    public record LoginRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 1024) String password) {}

    public record SessionView(boolean authenticated, UserView user, Instant expiresAt, Instant idleExpiresAt) {

        public static SessionView anonymous() {
            return new SessionView(false, null, null, null);
        }
    }

    public record UserView(
            String id, String username, String displayName, List<String> roles, List<String> permissions) {}
}
