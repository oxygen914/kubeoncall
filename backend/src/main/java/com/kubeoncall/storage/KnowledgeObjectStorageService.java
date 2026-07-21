package com.kubeoncall.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

@Service
public class KnowledgeObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeObjectStorageService.class);

    private final MinioClient minioClient;
    private final KubeOnCallProperties properties;

    public KnowledgeObjectStorageService(MinioClient minioClient, KubeOnCallProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public StoredDocumentReference store(String title, String content, String source) {
        String bucket = properties.getStorage().getMinio().getBucket();
        if (bucket == null || bucket.isBlank()) {
            return new StoredDocumentReference(null, null, false, "MinIO bucket is not configured");
        }
        String normalizedTitle =
                title == null || title.isBlank() ? "knowledge" : title.replaceAll("[^a-zA-Z0-9-_]+", "-");
        String objectKey = "knowledge/" + Instant.now().toEpochMilli() + "-" + normalizedTitle + ".txt";
        try {
            byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                            new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("text/plain; charset=UTF-8")
                    .build());
            return new StoredDocumentReference(objectKey, bucket, true, "Stored source document from " + source);
        } catch (Exception ex) {
            log.warn(
                    "Knowledge source storage failed: bucket={}, errorType={}",
                    bucket,
                    ex.getClass().getSimpleName());
            return new StoredDocumentReference(objectKey, bucket, false, "MinIO store failed");
        }
    }

    /**
     * Stores an uploaded JSONL source under a deterministic import prefix.
     *
     * <p>Unlike the legacy best-effort {@link #store} path, command callers need a durable source
     * reference before creating the MySQL import task, so failures are propagated.
     */
    public StoredDocumentReference storeJsonl(String importPublicId, String originalFilename, byte[] content) {
        String filename = safeFilename(originalFilename, "knowledge.jsonl");
        return storeStrict(
                "knowledge/imports/" + importPublicId + "/" + filename, content, "application/x-ndjson; charset=UTF-8");
    }

    /** Reads a source object for a retryable async import handler. */
    public String readText(String bucket, String objectKey) {
        requireReference(bucket, objectKey);
        try (InputStream input = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read knowledge object from MinIO", ex);
        }
    }

    /** Stores a line-oriented error report; a retry overwrites the same object key. */
    public StoredDocumentReference storeErrorReport(String importPublicId, String report) {
        return storeStrict(
                "knowledge/imports/" + importPublicId + "/errors.jsonl",
                report == null ? new byte[0] : report.getBytes(StandardCharsets.UTF_8),
                "application/x-ndjson; charset=UTF-8");
    }

    private StoredDocumentReference storeStrict(String objectKey, byte[] content, String contentType) {
        String bucket = properties.getStorage().getMinio().getBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("MinIO bucket is not configured");
        }
        byte[] bytes = content == null ? new byte[0] : content;
        try {
            minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                            new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
            return new StoredDocumentReference(objectKey, bucket, true, "Stored knowledge object");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to store knowledge object in MinIO", ex);
        }
    }

    public void remove(StoredDocumentReference reference) {
        if (reference == null
                || !reference.stored()
                || reference.bucket() == null
                || reference.bucket().isBlank()
                || reference.objectKey() == null
                || reference.objectKey().isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(reference.bucket())
                    .object(reference.objectKey())
                    .build());
        } catch (Exception ex) {
            log.warn(
                    "Knowledge source rollback failed: bucket={}, objectKey={}, errorType={}",
                    reference.bucket(),
                    reference.objectKey(),
                    ex.getClass().getSimpleName());
        }
    }

    private static String safeFilename(String originalFilename, String fallback) {
        String candidate =
                originalFilename == null || originalFilename.isBlank() ? fallback : originalFilename.replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        String basename = slash >= 0 ? candidate.substring(slash + 1) : candidate;
        String normalized = basename.replaceAll("[^a-zA-Z0-9._-]+", "-");
        return normalized.isBlank() ? fallback : normalized;
    }

    private static void requireReference(String bucket, String objectKey) {
        if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Knowledge object bucket and key are required");
        }
    }
}
