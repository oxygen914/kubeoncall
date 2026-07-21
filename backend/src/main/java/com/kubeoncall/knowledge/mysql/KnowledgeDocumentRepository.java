package com.kubeoncall.knowledge.mysql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit-SQL repository for knowledge metadata and immutable content versions. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class KnowledgeDocumentRepository {

    private static final String DOCUMENT_COLUMNS = """
            d.public_id, d.external_document_id, d.title, d.source_type, d.source_uri,
            d.dataset_version, d.status, d.metadata_json, v.public_id AS current_version_public_id,
            d.version, d.created_at, d.updated_at, d.deleted_at, d.delete_reason
            """;

    private static final String VERSION_COLUMNS = """
            v.public_id, d.public_id AS document_public_id, i.public_id AS import_public_id,
            v.version_no, v.checksum, v.content_type, v.size_bytes, v.object_bucket, v.object_key,
            v.es_index, v.es_document_id, v.index_status, v.embedding_model, v.embedding_version,
            v.embedding_dimensions, v.augmentation_model, v.augmentation_version,
            v.metadata_json, v.indexed_at, v.created_at
            """;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final boolean mysqlEnabled;

    public KnowledgeDocumentRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    public KnowledgeDocumentRecord createDocument(CreateDocument command) {
        String publicId = publicId(command.publicId(), "doc_");
        jdbcTemplate.update(
                """
                INSERT INTO koc_knowledge_document
                  (public_id, external_document_id, title, source_type, source_uri,
                   dataset_version, status, metadata_json, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                publicId,
                command.externalDocumentId(),
                command.title(),
                command.sourceType(),
                command.sourceUri(),
                command.datasetVersion(),
                json(command.metadata()),
                command.createdBy());
        return findDocument(publicId, true)
                .orElseThrow(
                        () -> new IllegalStateException("Created knowledge document is not readable: " + publicId));
    }

    public Optional<KnowledgeDocumentRecord> findDocument(String publicId, boolean includeDeleted) {
        String deletedPredicate = includeDeleted ? "" : " AND d.deleted_at IS NULL";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + DOCUMENT_COLUMNS
                            + " FROM koc_knowledge_document d"
                            + " LEFT JOIN koc_knowledge_document_version v ON v.id = d.current_version_id"
                            + " WHERE d.public_id = ?"
                            + deletedPredicate,
                    new DocumentRowMapper(objectMapper),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<KnowledgeDocumentRecord> findDocumentByExternalId(
            String sourceType, String externalDocumentId, boolean includeDeleted) {
        if (sourceType == null || sourceType.isBlank() || externalDocumentId == null || externalDocumentId.isBlank()) {
            return Optional.empty();
        }
        String deletedPredicate = includeDeleted ? "" : " AND d.deleted_at IS NULL";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + DOCUMENT_COLUMNS
                            + " FROM koc_knowledge_document d"
                            + " LEFT JOIN koc_knowledge_document_version v ON v.id = d.current_version_id"
                            + " WHERE d.source_type = ? AND d.external_document_id = ?"
                            + deletedPredicate,
                    new DocumentRowMapper(objectMapper),
                    sourceType,
                    externalDocumentId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public DocumentPage listDocuments(DocumentQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!query.includeDeleted()) {
            where.append(" AND d.deleted_at IS NULL");
        }
        appendEquals(where, args, "d.status", query.status());
        appendEquals(where, args, "d.source_type", query.sourceType());
        appendEquals(where, args, "d.dataset_version", query.datasetVersion());
        if (query.text() != null && !query.text().isBlank()) {
            where.append(" AND (d.title LIKE ? OR d.external_document_id LIKE ?)");
            String pattern = "%" + query.text().trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_knowledge_document d" + where, Long.class, args.toArray());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 200));
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(size);
        pagedArgs.add((page - 1) * size);
        List<KnowledgeDocumentRecord> rows = jdbcTemplate.query(
                "SELECT " + DOCUMENT_COLUMNS
                        + " FROM koc_knowledge_document d"
                        + " LEFT JOIN koc_knowledge_document_version v ON v.id = d.current_version_id"
                        + where
                        + " ORDER BY d.updated_at DESC, d.id DESC LIMIT ? OFFSET ?",
                new DocumentRowMapper(objectMapper),
                pagedArgs.toArray());
        return new DocumentPage(rows, count == null ? 0 : count);
    }

    public KnowledgeDocumentVersionRecord createVersion(CreateVersion command) {
        KnowledgeDocumentVersionRecord record = transactionTemplate.execute(status -> {
            Long documentId = jdbcTemplate.queryForObject("""
                    SELECT id FROM koc_knowledge_document
                     WHERE public_id = ? AND deleted_at IS NULL
                     FOR UPDATE
                    """, Long.class, command.documentPublicId());
            if (documentId == null) {
                throw new IllegalArgumentException("Unknown active knowledge document: " + command.documentPublicId());
            }
            Long importId = command.importPublicId() == null
                    ? null
                    : internalId("koc_knowledge_import", command.importPublicId());
            Integer versionNumber = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(version_no), 0) + 1"
                            + " FROM koc_knowledge_document_version WHERE document_id = ?",
                    Integer.class,
                    documentId);
            String publicId = publicId(command.publicId(), "docv_");
            jdbcTemplate.update(
                    """
                    INSERT INTO koc_knowledge_document_version
                      (public_id, document_id, import_id, version_no, checksum, content_type,
                       size_bytes, object_bucket, object_key, index_status, embedding_model,
                       embedding_version, embedding_dimensions, augmentation_model,
                       augmentation_version, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    publicId,
                    documentId,
                    importId,
                    versionNumber,
                    checksum(command.checksum()),
                    command.contentType(),
                    command.sizeBytes(),
                    command.objectBucket(),
                    command.objectKey(),
                    defaultValue(command.indexStatus(), "PENDING"),
                    command.embeddingModel(),
                    command.embeddingVersion(),
                    command.embeddingDimensions(),
                    command.augmentationModel(),
                    command.augmentationVersion(),
                    json(command.metadata()));
            Long versionId = internalId("koc_knowledge_document_version", publicId);
            int updated = jdbcTemplate.update("""
                    UPDATE koc_knowledge_document
                       SET current_version_id = ?, version = version + 1
                     WHERE id = ? AND deleted_at IS NULL
                    """, versionId, documentId);
            if (updated != 1) {
                throw new IllegalStateException("Knowledge document became unavailable: " + command.documentPublicId());
            }
            return findVersion(publicId)
                    .orElseThrow(
                            () -> new IllegalStateException("Created knowledge version is not readable: " + publicId));
        });
        if (record == null) {
            throw new IllegalStateException("Knowledge version transaction returned no record");
        }
        return record;
    }

    public Optional<KnowledgeDocumentVersionRecord> findVersion(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + VERSION_COLUMNS
                            + " FROM koc_knowledge_document_version v"
                            + " JOIN koc_knowledge_document d ON d.id = v.document_id"
                            + " LEFT JOIN koc_knowledge_import i ON i.id = v.import_id"
                            + " WHERE v.public_id = ?",
                    new VersionRowMapper(objectMapper),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<KnowledgeDocumentVersionRecord> listVersions(String documentPublicId) {
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS
                        + " FROM koc_knowledge_document_version v"
                        + " JOIN koc_knowledge_document d ON d.id = v.document_id"
                        + " LEFT JOIN koc_knowledge_import i ON i.id = v.import_id"
                        + " WHERE d.public_id = ? ORDER BY v.version_no DESC",
                new VersionRowMapper(objectMapper),
                documentPublicId);
    }

    public boolean softDelete(String publicId, long expectedVersion, Long deletedBy, String reason, Instant deletedAt) {
        return jdbcTemplate.update("""
                        UPDATE koc_knowledge_document
                           SET status = 'DELETED', deleted_at = ?, deleted_by = ?,
                               delete_reason = ?, version = version + 1
                         WHERE public_id = ? AND version = ? AND deleted_at IS NULL
                        """, deletedAt, deletedBy, reason, publicId, expectedVersion) == 1;
    }

    public boolean restore(String publicId, long expectedVersion) {
        return jdbcTemplate.update("""
                        UPDATE koc_knowledge_document
                           SET status = 'ACTIVE', deleted_at = NULL, deleted_by = NULL,
                               delete_reason = NULL, version = version + 1
                         WHERE public_id = ? AND version = ? AND deleted_at IS NOT NULL
                        """, publicId, expectedVersion) == 1;
    }

    private Long internalId(String table, String publicId) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE public_id = ?", Long.class, publicId);
        if (id == null) {
            throw new IllegalArgumentException("Unknown public id: " + publicId);
        }
        return id;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Metadata cannot be serialized", ex);
        }
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static byte[] checksum(String hex) {
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException("Checksum must be a 64-character SHA-256 hex value");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Checksum must be hexadecimal", ex);
        }
    }

    private static String publicId(String requested, String prefix) {
        return requested == null || requested.isBlank()
                ? prefix + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null
                ? null
                : resultSet.getTimestamp(column).toInstant();
    }

    private static final class DocumentRowMapper implements RowMapper<KnowledgeDocumentRecord> {

        private final ObjectMapper objectMapper;

        private DocumentRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public KnowledgeDocumentRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return new KnowledgeDocumentRecord(
                    resultSet.getString("public_id"),
                    resultSet.getString("external_document_id"),
                    resultSet.getString("title"),
                    resultSet.getString("source_type"),
                    resultSet.getString("source_uri"),
                    resultSet.getString("dataset_version"),
                    resultSet.getString("status"),
                    readMap(objectMapper, resultSet.getString("metadata_json")),
                    resultSet.getString("current_version_public_id"),
                    resultSet.getLong("version"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"),
                    instant(resultSet, "deleted_at"),
                    resultSet.getString("delete_reason"));
        }
    }

    private static final class VersionRowMapper implements RowMapper<KnowledgeDocumentVersionRecord> {

        private final ObjectMapper objectMapper;

        private VersionRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public KnowledgeDocumentVersionRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            byte[] digest = resultSet.getBytes("checksum");
            int dimensions = resultSet.getInt("embedding_dimensions");
            return new KnowledgeDocumentVersionRecord(
                    resultSet.getString("public_id"),
                    resultSet.getString("document_public_id"),
                    resultSet.getString("import_public_id"),
                    resultSet.getInt("version_no"),
                    digest == null ? null : HexFormat.of().formatHex(digest),
                    resultSet.getString("content_type"),
                    resultSet.getLong("size_bytes"),
                    resultSet.getString("object_bucket"),
                    resultSet.getString("object_key"),
                    resultSet.getString("es_index"),
                    resultSet.getString("es_document_id"),
                    resultSet.getString("index_status"),
                    resultSet.getString("embedding_model"),
                    resultSet.getString("embedding_version"),
                    resultSet.wasNull() ? null : dimensions,
                    resultSet.getString("augmentation_model"),
                    resultSet.getString("augmentation_version"),
                    readMap(objectMapper, resultSet.getString("metadata_json")),
                    instant(resultSet, "indexed_at"),
                    instant(resultSet, "created_at"));
        }
    }

    private static Map<String, Object> readMap(ObjectMapper objectMapper, String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid JSON persisted for knowledge metadata", ex);
        }
    }

    public record CreateDocument(
            String publicId,
            String externalDocumentId,
            String title,
            String sourceType,
            String sourceUri,
            String datasetVersion,
            Map<String, Object> metadata,
            Long createdBy) {}

    public record CreateVersion(
            String publicId,
            String documentPublicId,
            String importPublicId,
            String checksum,
            String contentType,
            long sizeBytes,
            String objectBucket,
            String objectKey,
            String indexStatus,
            String embeddingModel,
            String embeddingVersion,
            Integer embeddingDimensions,
            String augmentationModel,
            String augmentationVersion,
            Map<String, Object> metadata) {}

    public record DocumentQuery(
            String status,
            String sourceType,
            String datasetVersion,
            String text,
            boolean includeDeleted,
            int page,
            int size) {}

    public record DocumentPage(List<KnowledgeDocumentRecord> items, long total) {}
}
