package com.kubeoncall.web.api.v1.users;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.identity.BuiltInRole;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.identity.PasswordHasher;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/users/{id}/...} — user and role administration (WBS-4 closure). All commands
 * require {@code user:manage} and take the actor from the session (never the request body). Disabling
 * a user or changing a password bumps {@code auth_version} so the change immediately kills the user's
 * live sessions; role changes also invalidate sessions so the new permissions take effect immediately.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<IdentityRepository> repositoryProvider;
    private final PasswordHasher passwordHasher;
    private final OperationAuditWriter auditWriter;
    private final V1Security security;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public UserAdminController(
            ObjectProvider<IdentityRepository> repositoryProvider,
            PasswordHasher passwordHasher,
            OperationAuditWriter auditWriter,
            V1Security security,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.repositoryProvider = repositoryProvider;
        this.passwordHasher = passwordHasher;
        this.auditWriter = auditWriter;
        this.security = security;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @Transactional
    public ApiResponse<Map<String, Object>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateUserRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.USER_MANAGE);
        CommandIdempotency command = begin(
                actor,
                "POST:/api/v1/users",
                idempotencyKey,
                "create|" + request.username() + "|" + request.displayName() + "|" + request.email() + "|"
                        + request.role());
        if (command.replay() != null) {
            return command.replay();
        }
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
        writeAudit(actor, "user.create", publicId, Map.of(), data);
        return complete(command, ApiResponse.ok(data, RequestIdFilter.currentRequestId()), publicId);
    }

    @PostMapping("/{userId}/disable")
    @Transactional
    public ApiResponse<Map<String, Object>> disable(
            @PathVariable String userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        V1Principal actor = security.requirePermission(PermissionCode.USER_MANAGE);
        CommandIdempotency command = begin(
                actor,
                "POST:/api/v1/users/" + userId + "/disable",
                idempotencyKey,
                "disable|" + userId + "|" + ifMatch);
        if (command.replay() != null) {
            return command.replay();
        }
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        Map<String, Object> before = snapshot(user);
        long expectedVersion = expectedVersion(ifMatch, user);
        requireUpdated(repository.setStatusIfVersion(user.id(), "DISABLED", expectedVersion));
        ApiResponse<Map<String, Object>> response = ok(userId, "DISABLED", expectedVersion + 1);
        writeAudit(actor, "user.disable", userId, before, response.data());
        return complete(command, response, userId);
    }

    @PostMapping("/{userId}/enable")
    @Transactional
    public ApiResponse<Map<String, Object>> enable(
            @PathVariable String userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        V1Principal actor = security.requirePermission(PermissionCode.USER_MANAGE);
        CommandIdempotency command = begin(
                actor, "POST:/api/v1/users/" + userId + "/enable", idempotencyKey, "enable|" + userId + "|" + ifMatch);
        if (command.replay() != null) {
            return command.replay();
        }
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        Map<String, Object> before = snapshot(user);
        long expectedVersion = expectedVersion(ifMatch, user);
        requireUpdated(repository.setStatusIfVersion(user.id(), "ACTIVE", expectedVersion));
        ApiResponse<Map<String, Object>> response = ok(userId, "ACTIVE", expectedVersion + 1);
        writeAudit(actor, "user.enable", userId, before, response.data());
        return complete(command, response, userId);
    }

    @PostMapping("/{userId}/password")
    @Transactional
    public ApiResponse<Map<String, Object>> changePassword(
            @PathVariable String userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChangePasswordRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.USER_MANAGE);
        CommandIdempotency command = begin(
                actor,
                "POST:/api/v1/users/" + userId + "/password",
                idempotencyKey,
                "password|" + userId + "|" + ifMatch + "|" + request.password());
        if (command.replay() != null) {
            return command.replay();
        }
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        if (request.password() == null || request.password().length() < 12) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "Password must be at least 12 characters");
        }
        String hash = passwordHasher.hash(request.password().toCharArray());
        long expectedVersion = expectedVersion(ifMatch, user);
        requireUpdated(repository.changePasswordIfVersion(user.id(), hash, expectedVersion));
        ApiResponse<Map<String, Object>> response = ok(userId, user.status(), expectedVersion + 1);
        writeAudit(
                actor,
                "user.password.change",
                userId,
                snapshot(user),
                Map.of("passwordChanged", true, "version", expectedVersion + 1));
        return complete(command, response, userId);
    }

    @PostMapping("/{userId}/roles")
    @Transactional
    public ApiResponse<Map<String, Object>> assignRole(
            @PathVariable String userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RoleRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.USER_MANAGE);
        CommandIdempotency command = begin(
                actor,
                "POST:/api/v1/users/" + userId + "/roles",
                idempotencyKey,
                "assign-role|" + userId + "|" + ifMatch + "|" + request.role());
        if (command.replay() != null) {
            return command.replay();
        }
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        BuiltInRole role = parseRole(request.role());
        Map<String, Object> before = snapshot(user);
        long expectedVersion = expectedVersion(ifMatch, user);
        requireUpdated(repository.advanceAdminVersion(user.id(), expectedVersion));
        if (!user.roles().contains(role.name())) {
            repository.assignRole(user.id(), role.name());
        }
        Map<String, Object> after = snapshot(user, withRole(user, role.name(), true));
        after.put("version", expectedVersion + 1);
        writeAudit(actor, "user.role.assign", userId, before, after);
        return complete(command, ApiResponse.ok(after, RequestIdFilter.currentRequestId()), userId);
    }

    @DeleteMapping("/{userId}/roles/{role}")
    @Transactional
    public ApiResponse<Map<String, Object>> revokeRole(
            @PathVariable String userId,
            @PathVariable String role,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        V1Principal actor = security.requirePermission(PermissionCode.USER_MANAGE);
        CommandIdempotency command = begin(
                actor,
                "DELETE:/api/v1/users/" + userId + "/roles/" + role,
                idempotencyKey,
                "revoke-role|" + userId + "|" + role + "|" + ifMatch);
        if (command.replay() != null) {
            return command.replay();
        }
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        BuiltInRole parsedRole = parseRole(role);
        Map<String, Object> before = snapshot(user);
        long expectedVersion = expectedVersion(ifMatch, user);
        requireUpdated(repository.advanceAdminVersion(user.id(), expectedVersion));
        if (user.roles().contains(parsedRole.name())) {
            if (user.roles().size() == 1) {
                throw new V1ApiException(
                        HttpStatus.CONFLICT.value(), V1ApiErrorCode.CONFLICT, "A user must retain at least one role");
            }
            repository.revokeRole(user.id(), parsedRole.name());
        }
        Map<String, Object> after = snapshot(user, withRole(user, parsedRole.name(), false));
        after.put("version", expectedVersion + 1);
        writeAudit(actor, "user.role.revoke", userId, before, after);
        return complete(command, ApiResponse.ok(after, RequestIdFilter.currentRequestId()), userId);
    }

    @PostMapping("/{userId}/session-revocations")
    @Transactional
    public ApiResponse<Map<String, Object>> revokeSessions(
            @PathVariable String userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        V1Principal actor = security.requirePermission(PermissionCode.USER_MANAGE);
        CommandIdempotency command = begin(
                actor,
                "POST:/api/v1/users/" + userId + "/session-revocations",
                idempotencyKey,
                "revoke-sessions|" + userId + "|" + ifMatch);
        if (command.replay() != null) {
            return command.replay();
        }
        IdentityRepository repository = requireRepository();
        UserAccount user = loadByPublicId(repository, userId);
        long expectedVersion = expectedVersion(ifMatch, user);
        requireUpdated(repository.advanceAdminVersion(user.id(), expectedVersion));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", userId);
        data.put("sessionsRevokedAt", Instant.now().toString());
        data.put("version", expectedVersion + 1);
        writeAudit(actor, "user.sessions.revoke", userId, snapshot(user), data);
        return complete(command, ApiResponse.ok(data, RequestIdFilter.currentRequestId()), userId);
    }

    private void writeAudit(
            V1Principal actor, String action, String userId, Map<String, Object> before, Map<String, Object> after) {
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", actor.user().id(), actor.user().displayName())
                .action(action)
                .resource("user", userId)
                .before(before)
                .after(after)
                .requestId(RequestIdFilter.currentRequestId())
                .build());
    }

    private static Map<String, Object> snapshot(UserAccount user) {
        return snapshot(user, user.roles());
    }

    private static Map<String, Object> snapshot(UserAccount user, java.util.Collection<String> roles) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", user.publicId());
        snapshot.put("username", user.username());
        snapshot.put("status", user.status());
        snapshot.put("version", user.version());
        snapshot.put("roles", roles);
        return snapshot;
    }

    private static java.util.Set<String> withRole(UserAccount user, String role, boolean assigned) {
        java.util.Set<String> roles = new LinkedHashSet<>(user.roles());
        if (assigned) {
            roles.add(role);
        } else {
            roles.remove(role);
        }
        return roles;
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

    private static ApiResponse<Map<String, Object>> ok(String userId, String status, long version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", userId);
        data.put("status", status);
        data.put("version", version);
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private static long expectedVersion(String ifMatch, UserAccount user) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return user.version();
        }
        try {
            long version = Long.parseLong(ifMatch.replace("\"", "").trim());
            if (version <= 0) {
                throw invalidVersion();
            }
            return version;
        } catch (NumberFormatException ex) {
            throw invalidVersion();
        }
    }

    private static void requireUpdated(boolean updated) {
        if (!updated) {
            throw new V1ApiException(
                    HttpStatus.CONFLICT.value(), V1ApiErrorCode.CONFLICT, "User was modified by another request");
        }
    }

    private static V1ApiException invalidVersion() {
        return new V1ApiException(
                HttpStatus.BAD_REQUEST.value(),
                V1ApiErrorCode.INVALID_REQUEST,
                "If-Match must be a positive numeric version");
    }

    private CommandIdempotency begin(V1Principal actor, String route, String idempotencyKey, String canonicalRequest) {
        if (idempotencyKey == null
                || idempotencyKey.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must contain 16 to 128 characters");
        }
        IdempotencyService.IdempotencyScope scope =
                new IdempotencyService.IdempotencyScope("USER", actor.user().publicId(), route);
        IdempotencyService.BeginResult result = idempotencyService.begin(scope, idempotencyKey, canonicalRequest);
        return switch (result.action()) {
            case EXECUTE -> new CommandIdempotency(scope, idempotencyKey, null);
            case REPLAY -> new CommandIdempotency(scope, idempotencyKey, replay(result.responseJson()));
            case IN_PROGRESS ->
                throw new V1ApiException(
                        HttpStatus.CONFLICT.value(),
                        V1ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "An idempotent request for this key is already in progress");
            case REUSED ->
                throw new V1ApiException(
                        HttpStatus.CONFLICT.value(),
                        V1ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was used with a different request");
        };
    }

    private ApiResponse<Map<String, Object>> complete(
            CommandIdempotency command, ApiResponse<Map<String, Object>> response, String resourcePublicId) {
        if (command.scope() != null) {
            idempotencyService.succeed(command.scope(), command.key(), 200, response.data(), "user", resourcePublicId);
        }
        return response;
    }

    private ApiResponse<Map<String, Object>> replay(String responseJson) {
        try {
            return ApiResponse.ok(objectMapper.readValue(responseJson, MAP_TYPE), RequestIdFilter.currentRequestId());
        } catch (Exception ex) {
            throw new IllegalStateException("Stored user command response is invalid", ex);
        }
    }

    private record CommandIdempotency(
            IdempotencyService.IdempotencyScope scope, String key, ApiResponse<Map<String, Object>> replay) {}

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
