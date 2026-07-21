package com.kubeoncall.web.api.v1.users;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.BuiltInRole;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.identity.PasswordHasher;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/users/{id}/...} — user and role administration (WBS-4 closure). All commands
 * require {@code user:manage} and take the actor from the session (never the request body). Disabling
 * a user or changing a password bumps {@code auth_version} so the change immediately kills the user's
 * live sessions; role changes take effect on the next session permission reload.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private final ObjectProvider<IdentityRepository> repositoryProvider;
    private final PasswordHasher passwordHasher;
    private final V1Security security;

    public UserAdminController(
            ObjectProvider<IdentityRepository> repositoryProvider, PasswordHasher passwordHasher, V1Security security) {
        this.repositoryProvider = repositoryProvider;
        this.passwordHasher = passwordHasher;
        this.security = security;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateUserRequest request) {
        security.requirePermission(PermissionCode.USER_MANAGE);
        IdentityRepository repository = requireRepository();
        if (repository.usernameExists(request.username())) {
            throw new V1ApiException(HttpStatus.CONFLICT.value(), V1ApiErrorCode.CONFLICT, "Username already exists");
        }
        if (request.password() == null || request.password().length() < 12) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "Password must be at least 12 characters");
        }
        String publicId = "usr_" + UUID.randomUUID().toString().replace("-", "");
        String hash = passwordHasher.hash(request.password().toCharArray());
        BuiltInRole role = parseRole(request.role());
        long userId = repository.createUser(publicId, request.username(), request.displayName(), request.email(), hash);
        repository.assignRole(userId, role.name());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", publicId);
        data.put("username", request.username());
        data.put("role", role.name());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    @PostMapping("/{userId}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable String userId) {
        security.requirePermission(PermissionCode.USER_MANAGE);
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        repository.setStatus(user.id(), "DISABLED");
        return ok(userId, "DISABLED");
    }

    @PostMapping("/{userId}/enable")
    public ApiResponse<Map<String, Object>> enable(@PathVariable String userId) {
        security.requirePermission(PermissionCode.USER_MANAGE);
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        repository.setStatus(user.id(), "ACTIVE");
        return ok(userId, "ACTIVE");
    }

    @PostMapping("/{userId}/password")
    public ApiResponse<Map<String, Object>> changePassword(
            @PathVariable String userId, @Valid @RequestBody ChangePasswordRequest request) {
        security.requirePermission(PermissionCode.USER_MANAGE);
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        if (request.password() == null || request.password().length() < 12) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "Password must be at least 12 characters");
        }
        String hash = passwordHasher.hash(request.password().toCharArray());
        repository.changePassword(user.id(), hash);
        return ok(userId, user.status());
    }

    @PostMapping("/{userId}/roles")
    public ApiResponse<Map<String, Object>> assignRole(
            @PathVariable String userId, @Valid @RequestBody RoleRequest request) {
        security.requirePermission(PermissionCode.USER_MANAGE);
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        BuiltInRole role = parseRole(request.role());
        repository.assignRole(user.id(), role.name());
        return ok(userId, user.status());
    }

    @PostMapping("/{userId}/session-revocations")
    public ApiResponse<Map<String, Object>> revokeSessions(@PathVariable String userId) {
        security.requirePermission(PermissionCode.USER_MANAGE);
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        repository.invalidateSessions(user.id());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", userId);
        data.put("sessionsRevokedAt", Instant.now().toString());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private IdentityRepository requireRepository() {
        IdentityRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Identity repository is not available");
        }
        return repository;
    }

    private static UserAccount loadByPublicId(IdentityRepository repository, String publicId) {
        return repository.listUsers().stream()
                .filter(u -> publicId.equals(u.publicId()))
                .findFirst()
                .orElseThrow(() -> new V1ApiException(
                        HttpStatus.NOT_FOUND.value(), V1ApiErrorCode.NOT_FOUND, "User not found: " + publicId));
    }

    private static ApiResponse<Map<String, Object>> ok(String userId, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", userId);
        data.put("status", status);
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private static BuiltInRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return BuiltInRole.VIEWER;
        }
        try {
            return BuiltInRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(), V1ApiErrorCode.INVALID_REQUEST, "Unknown role: " + role);
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 128) String displayName,
            @Size(max = 320) String email,
            @Size(max = 1024) String password,
            String role) {}

    public record ChangePasswordRequest(@Size(max = 1024) String password) {}

    public record RoleRequest(@NotBlank String role) {}
}
