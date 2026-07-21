package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.BuiltInRole;
import com.kubeoncall.identity.CsrfService;
import com.kubeoncall.identity.IdentityAdminService;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.identity.PasswordHasher;
import com.kubeoncall.identity.SessionStore;
import com.kubeoncall.identity.UserAccount;

/**
 * Verifies the identity stack end-to-end against a real MySQL 8 container: Flyway runs on an empty
 * database, the role/permission seed is present, admin creation persists, login resolves against the
 * real repository and the session round-trips through the store. Runs under the
 * {@code integration-test} profile (Failsafe); skipped in the default gate.
 */
@Testcontainers
class IdentityMySqlIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final CsrfService csrfService = new CsrfService();

    @Test
    void flywaySeedsRolesAndAdminLoginRoundTrips() throws Exception {
        DataSource dataSource = DataSourceBuilder.create()
                .url(MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false")
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        runMigration(jdbcTemplate);

        // Seed roles/permissions are present.
        Integer roleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM koc_role", Integer.class);
        assertThat(roleCount).isEqualTo(3);
        Integer permissionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM koc_permission", Integer.class);
        assertThat(permissionCount).isGreaterThan(20);
        Integer adminPermissions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_role_permission rp JOIN koc_role r ON r.id = rp.role_id WHERE r.code = 'ADMIN'",
                Integer.class);
        assertThat(adminPermissions).isEqualTo(permissionCount);

        IdentityRepository repository = new IdentityRepository(jdbcTemplate, true);
        IdentityAdminService adminService = wrapAdminService(repository);

        IdentityAdminService.CreateUserResult created =
                adminService.createAdmin("admin", "Admin", "initial-password-123".toCharArray());
        assertThat(created.status()).isEqualTo(IdentityAdminService.CreateUserResult.Status.CREATED);

        // Duplicate create is a clean no-op.
        IdentityAdminService.CreateUserResult duplicate =
                adminService.createAdmin("admin", "Admin", "initial-password-123".toCharArray());
        assertThat(duplicate.status()).isEqualTo(IdentityAdminService.CreateUserResult.Status.ALREADY_EXISTS);

        // The created user carries the ADMIN role and the full permission set.
        Optional<UserAccount> loaded = repository.findByUsername("admin");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().roles()).contains(BuiltInRole.ADMIN.name());
        assertThat(loaded.get().permissions()).contains("user:manage");

        AuthService authService = wrapAuthService(repository, new InMemorySessionStore());

        AuthService.LoginResult badLogin =
                authService.login("admin", "wrong-password-here".toCharArray(), "127.0.0.1", "ua", "req_1");
        assertThat(badLogin.status()).isEqualTo(AuthService.LoginResult.Status.INVALID_CREDENTIALS);

        AuthService.LoginResult goodLogin =
                authService.login("admin", "initial-password-123".toCharArray(), "127.0.0.1", "ua", "req_2");
        assertThat(goodLogin.status()).isEqualTo(AuthService.LoginResult.Status.SUCCESS);
        assertThat(goodLogin.sessionId()).isNotBlank();

        Optional<AuthService.ResolvedSession> session = authService.resolveSession(goodLogin.sessionId());
        assertThat(session).isPresent();
        assertThat(session.get().user().username()).isEqualTo("admin");

        authService.logout(goodLogin.sessionId());
        assertThat(authService.resolveSession(goodLogin.sessionId())).isEmpty();

        // Login audit row was written.
        Integer auditRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM koc_login_audit", Integer.class);
        assertThat(auditRows).isGreaterThanOrEqualTo(2);
    }

    private void runMigration(JdbcTemplate jdbcTemplate) throws Exception {
        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    private IdentityAdminService wrapAdminService(IdentityRepository repository) {
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentityRepository> provider =
                (ObjectProvider<IdentityRepository>) new SingletonObjectProvider<>(repository);
        return new IdentityAdminService(provider, passwordHasher);
    }

    private AuthService wrapAuthService(IdentityRepository repository, SessionStore sessionStore) {
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentityRepository> repoProvider =
                (ObjectProvider<IdentityRepository>) new SingletonObjectProvider<>(repository);
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionStore> sessionProvider =
                (ObjectProvider<SessionStore>) new SingletonObjectProvider<>(sessionStore);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAuth().setLoginMaxAttempts(5);
        properties.getAuth().setLoginLockDurationSeconds(900);
        properties.getAuth().setSessionIdleTimeoutSeconds(1800);
        properties.getAuth().setSessionMaxTimeoutSeconds(28800);
        return new AuthService(repoProvider, sessionProvider, passwordHasher, csrfService, properties);
    }

    /** Minimal ObjectProvider returning a fixed instance, so AuthService can be wired in tests. */
    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T instance;

        SingletonObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }

    /** In-memory SessionStore so the login round-trip can be tested without Redis. */
    private static final class InMemorySessionStore implements SessionStore {

        private final java.util.Map<String, com.kubeoncall.identity.SessionRecord> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String create(com.kubeoncall.identity.SessionRecord record, Duration ttl) {
            String id = com.kubeoncall.identity.SessionIdGenerator.generate();
            store.put(id, record);
            return id;
        }

        @Override
        public Optional<com.kubeoncall.identity.SessionRecord> find(String sessionId) {
            return Optional.ofNullable(store.get(sessionId));
        }

        @Override
        public void replace(String sessionId, com.kubeoncall.identity.SessionRecord record, Duration ttl) {
            store.put(sessionId, record);
        }

        @Override
        public void delete(String sessionId) {
            store.remove(sessionId);
        }

        @Override
        public void deleteByUser(long userId) {
            store.entrySet().removeIf(e -> e.getValue().userId() == userId);
        }
    }
}
