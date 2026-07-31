package com.kubeoncall.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Orchestrates login, logout and session resolution for the {@code /api/v1} surface. Login is
 * deliberately constant-effort on the failure path (same response code, no user enumeration) while
 * the server-side audit distinguishes user-not-found, password-mismatch, locked and disabled. When
 * MySQL is not configured the service reports unavailable so the security chain can fall back to
 * legacy tokens rather than blocking the whole API.
 *
 * <p>Identity/session dependencies are injected via {@link ObjectProvider} because they are only
 * instantiated when {@code kubeoncall.mysql-enabled=true}; the service itself is always present so
 * the security chain can ask {@link #isAvailable()} without conditional wiring.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final ObjectProvider<IdentityRepository> userRepositoryProvider;
    private final ObjectProvider<SessionStore> sessionStoreProvider;
    private final PasswordHasher passwordHasher;
    private final CsrfService csrfService;
    private final KubeOnCallProperties properties;

    public AuthService(
            ObjectProvider<IdentityRepository> userRepositoryProvider,
            ObjectProvider<SessionStore> sessionStoreProvider,
            PasswordHasher passwordHasher,
            CsrfService csrfService,
            KubeOnCallProperties properties) {
        this.userRepositoryProvider = userRepositoryProvider;
        this.sessionStoreProvider = sessionStoreProvider;
        this.passwordHasher = passwordHasher;
        this.csrfService = csrfService;
        this.properties = properties;
    }

    public boolean isAvailable() {
        IdentityRepository repository = userRepositoryProvider.getIfAvailable();
        return repository != null && repository.isAvailable();
    }

    public LoginResult login(String username, char[] password, String sourceIp, String userAgent, String requestId) {
        IdentityRepository userRepository = userRepositoryProvider.getIfAvailable();
        SessionStore sessionStore = sessionStoreProvider.getIfAvailable();
        if (userRepository == null || !userRepository.isAvailable() || sessionStore == null) {
            return LoginResult.unavailable();
        }
        Instant now = Instant.now();
        Optional<UserAccount> userOpt = userRepository.findByUsername(username);
        UserAccount user = userOpt.orElse(null);

        if (user == null) {
            audit(userRepository, username, null, "FAILURE", "USER_NOT_FOUND", sourceIp, userAgent, requestId, now);
            return LoginResult.invalidCredentials();
        }
        if (user.isLocked(now)) {
            audit(
                    userRepository,
                    user.username(),
                    user.id(),
                    "FAILURE",
                    "ACCOUNT_LOCKED",
                    sourceIp,
                    userAgent,
                    requestId,
                    now);
            return LoginResult.accountLocked();
        }
        if (!user.isActive() && !user.isPasswordChangeRequired()) {
            audit(
                    userRepository,
                    user.username(),
                    user.id(),
                    "FAILURE",
                    "ACCOUNT_DISABLED",
                    sourceIp,
                    userAgent,
                    requestId,
                    now);
            return LoginResult.invalidCredentials();
        }

        boolean passwordOk = passwordHasher.matches(password, user.passwordHash());
        if (!passwordOk) {
            Instant lockedUntil = recordFailure(userRepository, user, now);
            String reason = lockedUntil == null ? "PASSWORD_MISMATCH" : "ACCOUNT_LOCKED";
            audit(userRepository, user.username(), user.id(), "FAILURE", reason, sourceIp, userAgent, requestId, now);
            return lockedUntil == null ? LoginResult.invalidCredentials() : LoginResult.accountLocked();
        }

        userRepository.recordLoginSuccess(user.id());
        SessionRecord session = newSession(user, now);
        Duration ttl = Duration.ofSeconds(properties.getAuth().getSessionMaxTimeoutSeconds());
        String sessionId = sessionStore.create(session, ttl);
        audit(userRepository, user.username(), user.id(), "SUCCESS", null, sourceIp, userAgent, requestId, now);
        return LoginResult.success(sessionId, session, user);
    }

    public Optional<ResolvedSession> resolveSession(String sessionId) {
        IdentityRepository userRepository = userRepositoryProvider.getIfAvailable();
        SessionStore sessionStore = sessionStoreProvider.getIfAvailable();
        if (userRepository == null || !userRepository.isAvailable() || sessionStore == null) {
            return Optional.empty();
        }
        Optional<SessionRecord> recordOpt = sessionStore.find(sessionId);
        if (recordOpt.isEmpty()) {
            return Optional.empty();
        }
        SessionRecord record = recordOpt.get();
        Instant now = Instant.now();
        long idle = properties.getAuth().getSessionIdleTimeoutSeconds();
        long max = properties.getAuth().getSessionMaxTimeoutSeconds();
        if (record.isExpired(now, idle)) {
            sessionStore.delete(sessionId);
            return Optional.empty();
        }
        Optional<UserAccount> userOpt = userRepository.findById(record.userId());
        if (userOpt.isEmpty()) {
            sessionStore.delete(sessionId);
            return Optional.empty();
        }
        UserAccount user = userOpt.get();
        if (!user.isActive() && !user.isPasswordChangeRequired()) {
            sessionStore.deleteByUser(user.id());
            return Optional.empty();
        }
        if (user.authVersion() != record.authVersion()) {
            sessionStore.delete(sessionId);
            return Optional.empty();
        }
        SessionRecord touched = record.touch(now, idle, max);
        sessionStore.replace(sessionId, touched, Duration.ofSeconds(Math.max(max, idle)));
        return Optional.of(new ResolvedSession(sessionId, touched, user));
    }

    public void logout(String sessionId) {
        SessionStore sessionStore = sessionStoreProvider.getIfAvailable();
        if (sessionId != null && sessionStore != null) {
            sessionStore.delete(sessionId);
        }
    }

    private SessionRecord newSession(UserAccount user, Instant now) {
        long max = properties.getAuth().getSessionMaxTimeoutSeconds();
        long idle = properties.getAuth().getSessionIdleTimeoutSeconds();
        Instant absoluteDeadline = now.plusSeconds(max);
        Instant idleDeadline = now.plusSeconds(idle);
        Instant expiresAt = absoluteDeadline.isBefore(idleDeadline) ? absoluteDeadline : idleDeadline;
        return new SessionRecord(
                user.id(),
                user.publicId(),
                user.username(),
                user.displayName(),
                user.authVersion(),
                now,
                now,
                expiresAt,
                csrfService.newSecret());
    }

    private Instant recordFailure(IdentityRepository userRepository, UserAccount user, Instant now) {
        int newCount = user.failedLoginCount() + 1;
        if (newCount >= properties.getAuth().getLoginMaxAttempts()) {
            Instant lockedUntil = now.plusSeconds(properties.getAuth().getLoginLockDurationSeconds());
            userRepository.recordLoginFailure(user.id(), lockedUntil);
            return lockedUntil;
        }
        userRepository.recordLoginFailure(user.id(), null);
        return null;
    }

    private void audit(
            IdentityRepository userRepository,
            String username,
            Long userId,
            String result,
            String reasonCode,
            String sourceIp,
            String userAgent,
            String requestId,
            Instant occurredAt) {
        try {
            userRepository.writeLoginAudit(new IdentityRepository.LoginAuditRecord(
                    "laud_" + UUID.randomUUID().toString().replace("-", ""),
                    userId,
                    username == null ? "" : username,
                    result,
                    reasonCode,
                    sourceIp,
                    userAgent,
                    requestId == null ? "" : requestId,
                    occurredAt));
        } catch (Exception ex) {
            log.warn("Failed to write login audit: {}", ex.getMessage());
        }
    }

    public record LoginResult(Status status, String sessionId, SessionRecord session, UserAccount user) {

        public static LoginResult invalidCredentials() {
            return new LoginResult(Status.INVALID_CREDENTIALS, null, null, null);
        }

        public static LoginResult accountLocked() {
            return new LoginResult(Status.ACCOUNT_LOCKED, null, null, null);
        }

        public static LoginResult unavailable() {
            return new LoginResult(Status.UNAVAILABLE, null, null, null);
        }

        public static LoginResult success(String sessionId, SessionRecord session, UserAccount user) {
            return new LoginResult(Status.SUCCESS, sessionId, session, user);
        }

        public enum Status {
            SUCCESS,
            INVALID_CREDENTIALS,
            ACCOUNT_LOCKED,
            UNAVAILABLE
        }
    }

    public record ResolvedSession(String sessionId, SessionRecord session, UserAccount user) {}

    public CsrfService csrfService() {
        return csrfService;
    }

    public java.util.List<UserAccount> listUsers() {
        IdentityRepository repository = userRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return java.util.List.of();
        }
        return new java.util.ArrayList<>(repository.listUsers());
    }

    public void revokeUserSessions(long userId) {
        IdentityRepository repository = userRepositoryProvider.getIfAvailable();
        SessionStore sessionStore = sessionStoreProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable() || sessionStore == null) {
            return;
        }
        repository.invalidateSessions(userId);
        sessionStore.deleteByUser(userId);
    }
}
