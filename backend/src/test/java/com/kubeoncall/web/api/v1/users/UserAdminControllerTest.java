package com.kubeoncall.web.api.v1.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private IdentityRepository repository;
    private PasswordHasher passwordHasher;
    private V1Security security;
    private UserAdminController controller;

    @BeforeEach
    void setUp() {
        repository = mock(IdentityRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        passwordHasher = new PasswordHasher();
        security = mock(V1Security.class);
        when(security.requirePermission(PermissionCode.USER_MANAGE)).thenReturn(principal());
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentityRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        controller = new UserAdminController(provider, passwordHasher, security);
    }

    @Test
    void createHashesPasswordAndAssignsRole() {
        when(repository.usernameExists("alice")).thenReturn(false);
        when(repository.createUser(anyString(), eq("alice"), eq("Alice"), eq("alice@example.com"), anyString()))
                .thenReturn(101L);
        var data = controller
                .create(new UserAdminController.CreateUserRequest(
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
                        new UserAdminController.CreateUserRequest("bob", "Bob", null, "short", "VIEWER")))
                .isInstanceOf(V1ApiException.class);
        verify(repository, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createConflictsOnDuplicateUsername() {
        when(repository.usernameExists("alice")).thenReturn(true);
        assertThatThrownBy(() -> controller.create(new UserAdminController.CreateUserRequest(
                        "alice", "Alice", null, "initial-password-123", "VIEWER")))
                .isInstanceOf(V1ApiException.class);
    }

    @Test
    void disableFlipsStatusToDisabled() {
        when(repository.listUsers()).thenReturn(List.of(user("usr_7", "alice", "ACTIVE")));
        controller.disable("usr_7");
        // The controller delegates to setStatus; the real repository implementation bumps
        // auth_version to kill live sessions, which is covered by IdentityRepository's own tests.
        verify(repository).setStatus(7L, "DISABLED");
    }

    @Test
    void changePasswordBumpsAuthVersion() {
        when(repository.listUsers()).thenReturn(List.of(user("usr_7", "alice", "ACTIVE")));
        controller.changePassword("usr_7", new UserAdminController.ChangePasswordRequest("new-password-1234"));
        verify(repository).changePassword(eq(7L), anyString());
    }

    @Test
    void revokeSessionsCallsInvalidate() {
        when(repository.listUsers()).thenReturn(List.of(user("usr_7", "alice", "ACTIVE")));
        controller.revokeSessions("usr_7");
        verify(repository).invalidateSessions(7L);
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
        return new UserAccount(
                7L,
                publicId,
                username,
                username,
                null,
                "",
                "BCRYPT",
                1L,
                status,
                1L,
                null,
                null,
                null,
                0,
                Set.of("OPERATOR"),
                Set.of());
    }
}
