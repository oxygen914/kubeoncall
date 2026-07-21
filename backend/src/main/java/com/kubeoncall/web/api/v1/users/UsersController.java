package com.kubeoncall.web.api.v1.users;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/users} — read-only user and role listing for the user-management page. Requires
 * {@code user:read}. Password hashes and auth versions are never serialized; the response exposes
 * only what an operator needs to identify and manage accounts. Write operations (create/disable/role
 * assignment) arrive in WBS-4 follow-up with audit and version handling.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    private final AuthService authService;
    private final V1Security security;

    public UsersController(AuthService authService, V1Security security) {
        this.authService = authService;
        this.security = security;
    }

    @GetMapping
    public ApiResponse<List<UserView>> list() {
        security.requirePermission(PermissionCode.USER_READ);
        List<UserView> users =
                authService.listUsers().stream().map(UsersController::toView).toList();
        return ApiResponse.ok(users, RequestIdFilter.currentRequestId());
    }

    private static UserView toView(UserAccount user) {
        return new UserView(
                user.publicId(),
                user.username(),
                user.displayName(),
                user.email(),
                user.status(),
                List.copyOf(user.roles()),
                user.lastLoginAt(),
                user.lockedUntil());
    }

    public record UserView(
            String id,
            String username,
            String displayName,
            String email,
            String status,
            List<String> roles,
            java.time.Instant lastLoginAt,
            java.time.Instant lockedUntil) {}
}
