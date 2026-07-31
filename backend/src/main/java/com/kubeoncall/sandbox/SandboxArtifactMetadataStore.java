package com.kubeoncall.sandbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;

/** Persists artifact metadata separately from the sandbox run lifecycle repository. */
final class SandboxArtifactMetadataStore {

    private static final String COLUMNS = """
            a.id, a.public_id, a.sandbox_run_id, a.artifact_type, a.bucket, a.object_key,
            a.content_type, a.size_bytes, a.sha256, a.classification, a.retention_until, a.created_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SandboxArtifactRecord> rowMapper = new ArtifactRowMapper();

    SandboxArtifactMetadataStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    SandboxArtifactRecord create(SandboxRunRepository.CreateArtifact command) {
        String publicId = publicId(command.publicId());
        jdbcTemplate.update(
                """
                INSERT INTO koc_sandbox_artifact
                  (public_id, sandbox_run_id, artifact_type, bucket, object_key, content_type,
                   size_bytes, sha256, classification, retention_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publicId,
                command.sandboxRunId(),
                command.artifactType().name(),
                command.bucket(),
                command.objectKey(),
                command.contentType(),
                command.sizeBytes(),
                command.sha256(),
                command.classification().name(),
                command.retentionUntil());
        return findByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("Created sandbox artifact is not readable: " + publicId));
    }

    List<SandboxArtifactRecord> findByRun(long sandboxRunId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM koc_sandbox_artifact a WHERE a.sandbox_run_id = ? ORDER BY a.id ASC",
                rowMapper,
                sandboxRunId);
    }

    Optional<SandboxArtifactRecord> findByPublicId(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM koc_sandbox_artifact a WHERE a.public_id = ?", rowMapper, publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    List<SandboxArtifactRecord> findExpired(Instant now, int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return jdbcTemplate.query(
                "SELECT "
                        + COLUMNS
                        + " FROM koc_sandbox_artifact a WHERE a.retention_until IS NOT NULL AND a.retention_until <= ? "
                        + "ORDER BY a.retention_until ASC LIMIT ?",
                rowMapper,
                now,
                limit);
    }

    private static String publicId(String requested) {
        return requested == null || requested.isBlank()
                ? "sbx_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static final class ArtifactRowMapper implements RowMapper<SandboxArtifactRecord> {
        @Override
        public SandboxArtifactRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SandboxArtifactRecord(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getLong("sandbox_run_id"),
                    SandboxArtifactType.valueOf(rs.getString("artifact_type")),
                    rs.getString("bucket"),
                    rs.getString("object_key"),
                    rs.getString("content_type"),
                    rs.getLong("size_bytes"),
                    rs.getString("sha256"),
                    SandboxClassification.valueOf(rs.getString("classification")),
                    SandboxRunRowMapper.instant(rs, "retention_until"),
                    SandboxRunRowMapper.instant(rs, "created_at"));
        }
    }
}
