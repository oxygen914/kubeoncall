package com.kubeoncall.web.api.v1.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.identity.BuiltInRole;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.identity.PasswordHasher;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/** Verifies the user-admin command path: create, disable (kills sessions), password change, role. */
class UserAdminControllerTest {

    private static final String IDEMPOTENCY_KEY = "user-command-key-123";

    private IdentityRepository repository;
    private PasswordHasher passwordHasher;
    private OperationAuditWriter auditWriter;
    private V1Security security;
    private IdempotencyService idempotencyService;
    private UserAdminController controller;

    @BeforeEach
    void setUp() {
        repository = mock(IdentityRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        passwordHasher = new PasswordHasher();
        auditWriter = mock(OperationAuditWriter.class);
        security = mock(V1Security.class);
        idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.begin(any(), anyString(), anyString()))
                .thenReturn(IdempotencyService.BeginResult.execute());
        when(security.requirePermission(PermissionCode.USER_MANAGE)).thenReturn(principal());
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentityRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        controller = new UserAdminController(
                provider, passwordHasher, auditWriter, security, idempotencyService, new ObjectMapper());
    }

    @Test
    void createHashesPasswordAndAssignsRole() {
        when(repository.usernameExists("alice")).thenReturn(false);
        when(repository.createUser(anyString(), eq("alice"), eq("Alice"), eq("alice@example.com"), anyString()))
                .thenReturn(101L);
        var data = controller
                .create(
                        IDEMPOTENCY_KEY,
                        new UserAdminController.CreateUserRequest(
                                "alice", "Alice", "alice@example.com", "initial-password-123", "OPERATOR"))
                .data();
        assertThat(data.get("username")).isEqualTo("alice");
        assertThat(data.get("role")).isEqualTo("OPERATOR");
        verify(repository).assignRole(101L, BuiltInRole.OPERATOR.name());
    }

    @Test
    void createRejectsShortPassword() {
        when(repository.usernameExists("bob")).thenReturn(false);
        assertThatThrownBy(() -> controller.create(
                        IDEMPOTENCY_KEY,
                        new UserAdminController.CreateUserRequest("bob", "Bob", null, "short", "VIEWER")))
                .isInstanceOf(V1ApiException.class);
        verify(repository, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createConflictsOnDuplicateUsername() {
        when(repository.usernameExists("alice")).thenReturn(true);
        assertThatThrownBy(() -> controller.create(
                        IDEMPOTENCY_KEY,
                        new UserAdminController.CreateUserRequest(
                                "alice", "Alice", null, "initial-password-123", "VIEWER")))
                .isInstanceOf(V1ApiException.class);
    }

    @Test
    void disableFlipsStatusToDisabled() {
        when(repository.listUsers()).thenReturn(List.of(user("usr_7", "alice", "ACTIVE")));
        when(repository.setStatusIfVersion(7L, "DISABLED", 1L)).thenReturn(true);
        controller.disable("usr_7", "1", IDEMPOTENCY_KEY);
        verify(repository).setStatusIfVersion(7L, "DISABLED", 1L);
    }

    @Test
    void changePasswordBumpsAuthVersion() {
        when(repository.listUsers()).thenReturn(List.of(user("usr_7", "alice", "ACTIVE")));
        when(repository.changePasswordIfVersion(eq(7L), anyString(), eq(1L))).thenReturn(true);
        controller.changePassword(
                "usr_7", "1", IDEMPOTENCY_KEY, new UserAdminController.ChangePasswordRequest("new-password-1234"));
        verify(repository).changePasswordIfVersion(eq(7L), anyString(), eq(1L));
    }

    @Test
    void revokeSessionsCallsInvalidate() {
        when(repository.listUsers()).thenReturn(List.of(user("usr_7", "alice", "ACTIVE")));
        when(repository.advanceAdminVersion(7L, 1L)).thenReturn(true);
        controller.revokeSessions("usr_7", "1", IDEMPOTENCY_KEY);
        verify(repository).advanceAdminVersion(7L, 1L);
    }

    @Test
    void revokeRoleRemovesBindingAndInvalidatesSessions() {
        when(repository.listUsers())
                .thenReturn(List.of(user("usr_7", "alice", "ACTIVE", Set.of("OPERATOR", "VIEWER"))));

        when(repository.advanceAdminVersion(7L, 1L)).thenReturn(true);
        controller.revokeRole("usr_7", "OPERATOR", "1", IDEMPOTENCY_KEY);

        verify(repository).revokeRole(7L, BuiltInRole.OPERATOR.name());
        verify(repository).advanceAdminVersion(7L, 1L);
        verify(auditWriter).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAStaleUserRevisionBeforeChangingRoles() {
        when(repository.listUsers())
                .thenReturn(List.of(user("usr_7", "alice", "ACTIVE", Set.of("OPERATOR", "VIEWER"))));
        when(repository.advanceAdminVersion(7L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> controller.revokeRole("usr_7", "OPERATOR", "1", IDEMPOTENCY_KEY))
                .isInstanceOf(V1ApiException.class)
                .hasMessage("User was modified by another request");

        verify(repository, never()).revokeRole(7L, BuiltInRole.OPERATOR.name());
        verify(auditWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistsAndReplaysUserCommandIdempotencyResults() {
        String key = IDEMPOTENCY_KEY;
        when(idempotencyService.begin(any(), eq(key), anyString()))
                .thenReturn(IdempotencyService.BeginResult.execute());
        when(repository.listUsers()).thenReturn(List.of(user("usr_7", "alice", "ACTIVE")));
        when(repository.setStatusIfVersion(7L, "DISABLED", 1L)).thenReturn(true);

        controller.disable("usr_7", "1", key);

        verify(idempotencyService).succeed(any(), eq(key), eq(200), any(), eq("user"), eq("usr_7"));

        when(idempotencyService.begin(any(), eq(key), anyString()))
                .thenReturn(IdempotencyService.BeginResult.replay(200, "{\"id\":\"usr_7\",\"status\":\"DISABLED\"}"));

        assertThat(controller.disable("usr_7", "1", key).data().get("status")).isEqualTo("DISABLED");
        verify(repository).setStatusIfVersion(7L, "DISABLED", 1L);
    }

    @Test
    void rejectsMissingIdempotencyKeyBeforeReadingOrWritingTheUser() {
        assertThatThrownBy(() -> controller.disable("usr_7", "1", null))
                .isInstanceOf(V1ApiException.class)
                .hasMessage("Idempotency-Key must contain 16 to 128 characters");

        verify(repository, never()).listUsers();
    }

    private static V1Principal principal() {
        UserAccount user = new UserAccount(
                1L,
                "usr_1",
                "admin",
                "Admin",
                null,
                "",
                "BCRYPT",
                1L,
                "ACTIVE",
                1L,
                null,
                null,
                null,
                0,
                Set.of("ADMIN"),
                Set.of(PermissionCode.USER_MANAGE));
        return new V1Principal(user, Set.of(PermissionCode.USER_MANAGE), V1Principal.AuthMethod.SESSION);
    }

    private static UserAccount user(String publicId, String username, String status) {
        return user(publicId, username, status, Set.of("OPERATOR"));
    }

    private static UserAccount user(String publicId, String username, String status, Set<String> roles) {
        return new UserAccount(
                7L, publicId, username, username, null, "", "BCRYPT", 1L, status, 1L, null, null, null, 0, roles,
                Set.of());
    }
}
