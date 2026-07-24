package com.kubeoncall.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MySQL-backed API token store (WBS-4 GAP-04-01). Only active when {@code kubeoncall.mysql-enabled=true}.
 * The plaintext token is generated on create, returned to the caller once, and never persisted: the
 * table holds only its SHA-256 hash (for future authentication lookup) and a short prefix (for display).
 * Revoke uses {@code (id, version, revoked_at IS NULL)} as a compare-and-set predicate.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class ApiTokenRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final SecureRandom RNG = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private static final String SELECT_COLUMNS = """
            t.public_id, t.owner_user_id, u.public_id AS owner_public_id, u.username AS owner_username,
            t.name, t.token_prefix, t.token_hash, t.scopes_json, t.expires_at, t.last_used_at,
            t.revoked_at, t.version, t.created_at
            """;

    private static final String SELECT_BY_OWNER = """
            SELECT %s FROM koc_api_token t
              JOIN koc_user u ON u.id = t.owner_user_id
             WHERE t.owner_user_id = ? AND u.deleted_at IS NULL
             ORDER BY t.created_at DESC, t.id DESC
            """.formatted(SELECT_COLUMNS);

    private static final String SELECT_ALL = """
            SELECT %s FROM koc_api_token t
              JOIN koc_user u ON u.id = t.owner_user_id
             WHERE u.deleted_at IS NULL
             ORDER BY t.created_at DESC, t.id DESC
            """.formatted(SELECT_COLUMNS);

    private static final String SELECT_BY_PUBLIC_ID = """
            SELECT %s FROM koc_api_token t
              JOIN koc_user u ON u.id = t.owner_user_id
             WHERE t.public_id = ? AND u.deleted_at IS NULL
            """.formatted(SELECT_COLUMNS);

    private static final String INSERT_TOKEN = """
            INSERT INTO koc_api_token
              (public_id, owner_user_id, name, token_prefix, token_hash, scopes_json, expires_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public ApiTokenRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    /** Lists tokens owned by the given user, newest first. */
    public List<ApiTokenRow> listByOwner(long ownerId) {
        return jdbcTemplate.query(SELECT_BY_OWNER, new TokenRowMapper(objectMapper), ownerId);
    }

    /** Lists every token (admin view), newest first. */
    public List<ApiTokenRow> listAll() {
        return jdbcTemplate.query(SELECT_ALL, new TokenRowMapper(objectMapper));
    }

    public Optional<ApiTokenRow> findByPublicId(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(SELECT_BY_PUBLIC_ID, new TokenRowMapper(objectMapper), publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Persists a new token. The plaintext is returned via {@link GeneratedToken} to the caller once;
     * only the hash and prefix are stored.
     */
    public GeneratedToken create(long ownerId, String name, List<String> scopes, Instant expiresAt) {
        String plaintext = generatePlaintext();
        String publicId = "tok_" + UUID.randomUUID().toString().replace("-", "");
        byte[] hash = sha256(plaintext);
        jdbcTemplate.update(
                INSERT_TOKEN,
                publicId,
                ownerId,
                name,
                prefixOf(plaintext),
                hash,
                encodeScopes(scopes),
                Timestamp.from(expiresAt));
        return new GeneratedToken(publicId, plaintext, prefixOf(plaintext));
    }

    /**
     * Revokes a token only if its version still matches and it is not already revoked.
     *
     * @return {@code true} if the row was revoked by this call
     */
    public boolean revokeIfVersion(long internalId, long expectedVersion, long revokedBy, String reason) {
        return jdbcTemplate.update("""
                        UPDATE koc_api_token
                           SET revoked_at = ?, revoked_by = ?, revoke_reason = ?, version = version + 1
                         WHERE id = ? AND version = ? AND revoked_at IS NULL
                        """, Timestamp.from(Instant.now()), revokedBy, reason, internalId, expectedVersion)
                == 1;
    }

    /**
     * Revokes a token by its public id only if its version still matches and it is not already revoked.
     *
     * @return {@code true} if the row was revoked by this call
     */
    public boolean revokeByPublicIdIfVersion(String publicId, long expectedVersion, long revokedBy) {
        return jdbcTemplate.update("""
                        UPDATE koc_api_token
                           SET revoked_at = ?, revoked_by = ?, version = version + 1
                         WHERE public_id = ? AND version = ? AND revoked_at IS NULL
                        """, Timestamp.from(Instant.now()), revokedBy, publicId, expectedVersion) == 1;
    }

    /** Looks up a live token by its SHA-256 hash (for the future authentication path). */
    public Optional<ApiTokenRow> findByHash(byte[] hash) {
        if (hash == null || hash.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM koc_api_token t"
                            + " JOIN koc_user u ON u.id = t.owner_user_id"
                            + " WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > ?",
                    new TokenRowMapper(objectMapper),
                    hash,
                    Timestamp.from(Instant.now())));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Records the last use of a token (for the future authentication path). */
    public void recordUsage(long internalId, String sourceIp) {
        jdbcTemplate.update(
                "UPDATE koc_api_token SET last_used_at = ?, last_used_ip = INET6_ATON(?) WHERE id = ?",
                Timestamp.from(Instant.now()),
                sourceIp,
                internalId);
    }

    private String encodeScopes(List<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes == null ? List.of() : scopes);
        } catch (Exception ex) {
            return "[]";
        }
    }

    /** Generates {@code koc_<32 random bytes base64url>} (≈45 chars). */
    private static String generatePlaintext() {
        byte[] bytes = new byte[SECRET_BYTES];
        RNG.nextBytes(bytes);
        return "koc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String prefixOf(String plaintext) {
        return plaintext.length() <= 12 ? plaintext : plaintext.substring(0, 12);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable for API token hashing", ex);
        }
    }

    private static final class TokenRowMapper implements RowMapper<ApiTokenRow> {

        private final ObjectMapper objectMapper;

        TokenRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ApiTokenRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ApiTokenRow(
                    rs.getString("public_id"),
                    rs.getLong("owner_user_id"),
                    rs.getString("owner_public_id"),
                    rs.getString("owner_username"),
                    rs.getString("name"),
                    rs.getString("token_prefix"),
                    decodeScopes(rs.getString("scopes_json")),
                    toInstant(rs.getTimestamp("expires_at")),
                    toInstant(rs.getTimestamp("last_used_at")),
                    toInstant(rs.getTimestamp("revoked_at")),
                    rs.getLong("version"),
                    toInstant(rs.getTimestamp("created_at")));
        }

        private List<String> decodeScopes(String json) {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            try {
                List<String> scopes = objectMapper.readValue(json, STRING_LIST);
                return scopes == null ? List.of() : List.copyOf(scopes);
            } catch (Exception ex) {
                return List.of();
            }
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Persisted token row. Never carries the plaintext or the raw hash bytes. */
    public record ApiTokenRow(
            String publicId,
            long ownerUserId,
            String ownerPublicId,
            String ownerUsername,
            String name,
            String tokenPrefix,
            List<String> scopes,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            long version,
            Instant createdAt) {

        public boolean revoked() {
            return revokedAt != null;
        }
    }

    /** Result of {@link #create}: the public id, the one-time plaintext and the display prefix. */
    public record GeneratedToken(String publicId, String plaintext, String tokenPrefix) {}
}
