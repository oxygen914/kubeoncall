package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.CsrfService;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.identity.PasswordHasher;
import com.kubeoncall.identity.SessionRecord;
import com.kubeoncall.identity.SessionStore;
import com.kubeoncall.identity.UserAccount;

class AuthServiceTest {

    private static final String PASSWORD = "alice-password-123";

    private IdentityRepository repository;
    private SessionStore sessionStore;
    private AuthService authService;
    private PasswordHasher passwordHasher;

    @BeforeEach
    void setUp() {
        repository = mock(IdentityRepository.class);
        sessionStore = mock(SessionStore.class);
        when(repository.isAvailable()).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentityRepository> repoProvider = Mockito.mock(ObjectProvider.class);
        when(repoProvider.getIfAvailable()).thenReturn(repository);
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionStore> sessionProvider = Mockito.mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(sessionStore);
        passwordHasher = new PasswordHasher();
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAuth().setLoginMaxAttempts(5);
        properties.getAuth().setLoginLockDurationSeconds(900);
        properties.getAuth().setSessionIdleTimeoutSeconds(1800);
        properties.getAuth().setSessionMaxTimeoutSeconds(28800);
        authService = new AuthService(repoProvider, sessionProvider, passwordHasher, new CsrfService(), properties);
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        UserAccount user = userWithPassword("alice", "ACTIVE", 0, null);
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(sessionStore.create(any(), any())).thenReturn("koc_sessionid");

        AuthService.LoginResult result = authService.login("alice", PASSWORD.toCharArray(), "127.0.0.1", "ua", "req_1");

        assertThat(result.status()).isEqualTo(AuthService.LoginResult.Status.SUCCESS);
        assertThat(result.sessionId()).isEqualTo("koc_sessionid");
        verify(repository).recordLoginSuccess(user.id());
        verify(sessionStore).create(any(), any());
    }

    @Test
    void loginFailsWithInvalidCredentialsAndNoUserEnumeration() {
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());

        AuthService.LoginResult result =
                authService.login("ghost", "any-password-here".toCharArray(), "127.0.0.1", "ua", "req_2");

        assertThat(result.status()).isEqualTo(AuthService.LoginResult.Status.INVALID_CREDENTIALS);
        verify(sessionStore, never()).create(any(), any());
    }

    @Test
    void loginFailsWithWrongPassword() {
        UserAccount user = userWithPassword("bob", "ACTIVE", 0, null);
        when(repository.findByUsername("bob")).thenReturn(Optional.of(user));

        AuthService.LoginResult result =
                authService.login("bob", "wrong-password-here".toCharArray(), "127.0.0.1", "ua", "req_3");

        assertThat(result.status()).isEqualTo(AuthService.LoginResult.Status.INVALID_CREDENTIALS);
        verify(repository).recordLoginFailure(eq(user.id()), eq(null));
    }

    @Test
    void loginLocksAfterMaxAttempts() {
        UserAccount user = userWithPassword("bob", "ACTIVE", 4, null);
        when(repository.findByUsername("bob")).thenReturn(Optional.of(user));

        AuthService.LoginResult result =
                authService.login("bob", "wrong-password-here".toCharArray(), "127.0.0.1", "ua", "req_3");

        assertThat(result.status()).isEqualTo(AuthService.LoginResult.Status.ACCOUNT_LOCKED);
        verify(repository).recordLoginFailure(eq(user.id()), any());
    }

    @Test
    void resolveSessionRejectsStaleAuthVersion() {
        long userId = 7L;
        when(sessionStore.find("koc_sessionid"))
                .thenReturn(Optional.of(new SessionRecord(
                        userId,
                        "usr_7",
                        "alice",
                        "Alice",
                        1L,
                        Instant.now(),
                        Instant.now(),
                        Instant.now().plusSeconds(3600),
                        "secret")));
        // Session was issued at authVersion=1 but the user's authVersion is now 2 (e.g. password
        // changed), so the session must be rejected and deleted.
        UserAccount staleAuthVersion = new UserAccount(
                userId,
                "usr_7",
                "alice",
                "Alice",
                null,
                passwordHasher.hash(PASSWORD.toCharArray()),
                "BCRYPT",
                1L,
                "ACTIVE",
                2L,
                null,
                null,
                null,
                0,
                Set.of(),
                Set.of());
        when(repository.findById(userId)).thenReturn(Optional.of(staleAuthVersion));

        Optional<AuthService.ResolvedSession> resolved = authService.resolveSession("koc_sessionid");

        assertThat(resolved).isEmpty();
        verify(sessionStore).delete("koc_sessionid");
    }

    @Test
    void resolveSessionReturnsEmptyWhenUnavailable() {
        when(repository.isAvailable()).thenReturn(false);
        assertThat(authService.resolveSession("koc_sessionid")).isEmpty();
    }

    private UserAccount userWithPassword(String username, String status, int failedCount, Instant lockedUntil) {
        String hash = passwordHasher.hash(PASSWORD.toCharArray());
        return new UserAccount(
                7L,
                "usr_7",
                username,
                username,
                null,
                hash,
                "BCRYPT",
                1L,
                status,
                1L,
                null,
                null,
                lockedUntil,
                failedCount,
                Set.of("ADMIN"),
                Set.of());
    }
}
