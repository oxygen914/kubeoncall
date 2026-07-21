package com.kubeoncall.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed identity repository. Only active when {@code kubeoncall.mysql-enabled=true} so the
 * rest of the application keeps running without a database during the incremental rollout. Queries
 * are explicit SQL to match the data-access ADR and to keep N+1 out of the permission load: a
 * single user fetch plus two set fetches by user id.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class IdentityRepository {

    private static final String SELECT_USER_BY_USERNAME = """
            SELECT id, public_id, username, display_name, email, password_hash, password_algorithm,
                   password_version, status, auth_version, password_changed_at, last_login_at,
                   locked_until, failed_login_count
              FROM koc_user
             WHERE username_normalized = ? AND deleted_at IS NULL
            """;

    private static final String SELECT_USER_BY_ID = """
            SELECT id, public_id, username, display_name, email, password_hash, password_algorithm,
                   password_version, status, auth_version, password_changed_at, last_login_at,
                   locked_until, failed_login_count
              FROM koc_user
             WHERE id = ? AND deleted_at IS NULL
            """;

    private static final String SELECT_ROLES_BY_USER =
            "SELECT r.code FROM koc_user_role ur JOIN koc_role r ON r.id = ur.role_id WHERE ur.user_id = ?";

    private static final String SELECT_PERMISSIONS_BY_USER = """
            SELECT DISTINCT p.code
              FROM koc_user_role ur
              JOIN koc_role_permission rp ON rp.role_id = ur.role_id
              JOIN koc_permission p ON p.id = rp.permission_id
             WHERE ur.user_id = ?
            """;

    private static final String UPDATE_LOGIN_SUCCESS =
            "UPDATE koc_user SET failed_login_count = 0, locked_until = NULL, last_login_at = ? WHERE id = ?";

    private static final String UPDATE_LOGIN_FAILURE =
            "UPDATE koc_user SET failed_login_count = failed_login_count + 1, locked_until = ? WHERE id = ?";

    private static final String INSERT_USER = """
            INSERT INTO koc_user
              (public_id, username, username_normalized, display_name, email, password_hash,
               password_algorithm, password_version, status, auth_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'ACTIVE', 1)
            """;

    private static final String INSERT_USER_ROLE =
            "INSERT INTO koc_user_role (user_id, role_id) SELECT ?, id FROM koc_role WHERE code = ?";

    private static final String SELECT_ROLE_EXISTS =
            "SELECT COUNT(*) FROM koc_role WHERE code = ? AND deleted_at IS NULL";

    private static final String DELETE_SESSIONS_BY_AUTH_VERSION =
            "UPDATE koc_user SET auth_version = auth_version + 1 WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final boolean mysqlEnabled;

    public IdentityRepository(
            JdbcTemplate jdbcTemplate, @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try {
            UserAccount account =
                    jdbcTemplate.queryForObject(SELECT_USER_BY_USERNAME, new UserRowMapper(), normalize(username));
            return Optional.ofNullable(account).map(this::withAuthorization);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<UserAccount> findById(long id) {
        try {
            UserAccount account = jdbcTemplate.queryForObject(SELECT_USER_BY_ID, new UserRowMapper(), id);
            return Optional.ofNullable(account).map(this::withAuthorization);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void recordLoginSuccess(long userId) {
        jdbcTemplate.update(UPDATE_LOGIN_SUCCESS, Instant.now(), userId);
    }

    public void recordLoginFailure(long userId, Instant lockedUntil) {
        jdbcTemplate.update(UPDATE_LOGIN_FAILURE, lockedUntil == null ? null : lockedUntil, userId);
    }

    public long createUser(String publicId, String username, String displayName, String email, String passwordHash) {
        jdbcTemplate.update(
                INSERT_USER, publicId, username, normalize(username), displayName, email, passwordHash, "BCRYPT");
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) {
            throw new IllegalStateException("Failed to retrieve generated user id");
        }
        return id;
    }

    public void assignRole(long userId, String roleCode) {
        Integer exists = jdbcTemplate.queryForObject(SELECT_ROLE_EXISTS, Integer.class, roleCode);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("Unknown role: " + roleCode);
        }
        jdbcTemplate.update(INSERT_USER_ROLE, userId, roleCode);
    }

    /** Revoke a single role from a user. No-op if the binding does not exist. */
    public void revokeRole(long userId, String roleCode) {
        jdbcTemplate.update(
                "DELETE ur FROM koc_user_role ur JOIN koc_role r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = ?",
                userId,
                roleCode);
    }

    /** Flip a user's status (ACTIVE/DISABLED). Disabling bumps auth version so live sessions die. */
    public void setStatus(long userId, String status) {
        jdbcTemplate.update("UPDATE koc_user SET status = ? WHERE id = ?", status, userId);
        if ("DISABLED".equals(status)) {
            invalidateSessions(userId);
        }
    }

    /** Change a user's password hash and bump password/auth version so old sessions and tokens die. */
    public void changePassword(long userId, String passwordHash) {
        jdbcTemplate.update(
                "UPDATE koc_user SET password_hash = ?, password_version = password_version + 1, "
                        + "auth_version = auth_version + 1, password_changed_at = ? WHERE id = ?",
                passwordHash,
                Timestamp.from(Instant.now()),
                userId);
    }

    /** Bump auth version so cached sessions for this user are invalidated on next request. */
    public void invalidateSessions(long userId) {
        jdbcTemplate.update(DELETE_SESSIONS_BY_AUTH_VERSION, userId);
    }

    public boolean usernameExists(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_user WHERE username_normalized = ?", Integer.class, normalize(username));
        return count != null && count > 0;
    }

    private UserAccount withAuthorization(UserAccount partial) {
        Set<String> roles = new LinkedHashSet<>(queryForList(SELECT_ROLES_BY_USER, partial.id()));
        Set<String> permissions = new LinkedHashSet<>(queryForList(SELECT_PERMISSIONS_BY_USER, partial.id()));
        return new UserAccount(
                partial.id(),
                partial.publicId(),
                partial.username(),
                partial.displayName(),
                partial.email(),
                partial.passwordHash(),
                partial.passwordAlgorithm(),
                partial.passwordVersion(),
                partial.status(),
                partial.authVersion(),
                partial.passwordChangedAt(),
                partial.lastLoginAt(),
                partial.lockedUntil(),
                partial.failedLoginCount(),
                roles,
                permissions);
    }

    private List<String> queryForList(String sql, long userId) {
        List<String> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(1), userId);
        return rows.stream().filter(r -> r != null && !r.isBlank()).toList();
    }

    private static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase();
    }

    private static final class UserRowMapper implements RowMapper<UserAccount> {

        @Override
        public UserAccount mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new UserAccount(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    rs.getString("email"),
                    rs.getString("password_hash"),
                    rs.getString("password_algorithm"),
                    rs.getLong("password_version"),
                    rs.getString("status"),
                    rs.getLong("auth_version"),
                    rs.getTimestamp("password_changed_at") == null
                            ? null
                            : rs.getTimestamp("password_changed_at").toInstant(),
                    rs.getTimestamp("last_login_at") == null
                            ? null
                            : rs.getTimestamp("last_login_at").toInstant(),
                    rs.getTimestamp("locked_until") == null
                            ? null
                            : rs.getTimestamp("locked_until").toInstant(),
                    rs.getInt("failed_login_count"),
                    Set.of(),
                    Set.of());
        }
    }

    /** Returned by login audit writes; kept minimal for the controller surface. */
    public record LoginAuditRecord(
            String publicId,
            Long userId,
            String usernameSnapshot,
            String result,
            String reasonCode,
            String sourceIp,
            String userAgent,
            String requestId,
            Instant occurredAt) {}

    public void writeLoginAudit(LoginAuditRecord record) {
        jdbcTemplate.update(
                """
                INSERT INTO koc_login_audit
                  (public_id, user_id, username_snapshot, result, reason_code, source_ip, user_agent, request_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, INET6_ATON(?), ?, ?, ?)
                """,
                record.publicId(),
                record.userId(),
                record.usernameSnapshot(),
                record.result(),
                record.reasonCode(),
                record.sourceIp(),
                truncate(record.userAgent(), 512),
                record.requestId(),
                record.occurredAt());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Collection<UserAccount> listUsers() {
        List<UserAccount> users = jdbcTemplate.query(
                "SELECT id, public_id, username, display_name, email, password_hash, password_algorithm, "
                        + "password_version, status, auth_version, password_changed_at, last_login_at, "
                        + "locked_until, failed_login_count FROM koc_user WHERE deleted_at IS NULL ORDER BY id",
                new UserRowMapper());
        return users.stream().map(this::withAuthorization).toList();
    }
}
