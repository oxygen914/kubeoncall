package com.kubeoncall.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.observability.DependencyCircuitBreaker;
import com.kubeoncall.service.KubeOnCallMetricsService;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

@Service
public class KnowledgeObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeObjectStorageService.class);

    private final MinioClient minioClient;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;
    private final DependencyCircuitBreaker circuitBreaker;

    public KnowledgeObjectStorageService(MinioClient minioClient, KubeOnCallProperties properties) {
        this(minioClient, properties, null);
    }

    public KnowledgeObjectStorageService(
            MinioClient minioClient, KubeOnCallProperties properties, KubeOnCallMetricsService metricsService) {
        this(minioClient, properties, metricsService, null);
    }

    @Autowired
    public KnowledgeObjectStorageService(
            MinioClient minioClient,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            DependencyCircuitBreaker circuitBreaker) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.metricsService = metricsService;
        this.circuitBreaker = circuitBreaker;
    }

    public StoredDocumentReference store(String title, String content, String source) {
        String bucket = properties.getStorage().getMinio().getBucket();
        if (bucket == null || bucket.isBlank()) {
            return new StoredDocumentReference(null, null, false, "MinIO bucket is not configured");
        }
        String normalizedTitle =
                title == null || title.isBlank() ? "knowledge" : title.replaceAll("[^a-zA-Z0-9-_]+", "-");
        String objectKey = "knowledge/" + Instant.now().toEpochMilli() + "-" + normalizedTitle + ".txt";
        long startedAt = System.nanoTime();
        try {
            byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            minio(
                    "put",
                    () -> minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                                    new ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType("text/plain; charset=UTF-8")
                            .build()));
            recordMinio("put", "success", startedAt);
            return new StoredDocumentReference(objectKey, bucket, true, "Stored source document from " + source);
        } catch (Exception ex) {
            recordMinio("put", "error", startedAt);
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
        long startedAt = System.nanoTime();
        try (InputStream input = minio(
                "get",
                () -> minioClient.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(objectKey).build()))) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            recordMinio("get", "success", startedAt);
            return content;
        } catch (Exception ex) {
            recordMinio("get", "error", startedAt);
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
        long startedAt = System.nanoTime();
        try {
            minio(
                    "put",
                    () -> minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                                    new ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType(contentType)
                            .build()));
            recordMinio("put", "success", startedAt);
            return new StoredDocumentReference(objectKey, bucket, true, "Stored knowledge object");
        } catch (Exception ex) {
            recordMinio("put", "error", startedAt);
            throw new IllegalStateException("Failed to store knowledge object in MinIO", ex);
        }
    }

    public boolean remove(StoredDocumentReference reference) {
        if (reference == null
                || !reference.stored()
                || reference.bucket() == null
                || reference.bucket().isBlank()
                || reference.objectKey() == null
                || reference.objectKey().isBlank()) {
            return false;
        }
        long startedAt = System.nanoTime();
        try {
            minio("delete", () -> {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(reference.bucket())
                        .object(reference.objectKey())
                        .build());
                return null;
            });
            recordMinio("delete", "success", startedAt);
            return true;
        } catch (Exception ex) {
            recordMinio("delete", "error", startedAt);
            log.warn(
                    "Knowledge source rollback failed: bucket={}, objectKey={}, errorType={}",
                    reference.bucket(),
                    reference.objectKey(),
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Lists old import objects so a scheduled reconciler can clean sources left behind by a process
     * death between MinIO upload and the MySQL transaction. The caller must compare these keys with
     * durable metadata before deletion.
     */
    public List<String> listObjectKeysOlderThan(String prefix, Instant olderThan) {
        String bucket = properties.getStorage().getMinio().getBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("MinIO bucket is not configured");
        }
        long startedAt = System.nanoTime();
        try {
            List<String> keys = new ArrayList<>();
            Iterable<Result<Item>> objects = minio(
                    "list",
                    () -> minioClient.listObjects(ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix == null ? "" : prefix)
                            .recursive(true)
                            .build()));
            for (Result<Item> result : objects) {
                Item item = result.get();
                if (!item.isDir()
                        && item.objectName() != null
                        && (olderThan == null
                                || item.lastModified() == null
                                || item.lastModified().toInstant().isBefore(olderThan))) {
                    keys.add(item.objectName());
                }
            }
            recordMinio("list", "success", startedAt);
            return List.copyOf(keys);
        } catch (Exception ex) {
            recordMinio("list", "error", startedAt);
            throw new IllegalStateException("Failed to list knowledge objects from MinIO", ex);
        }
    }

    private void recordMinio(String operation, String outcome, long startedAt) {
        if (metricsService != null) {
            metricsService.recordDependency(
                    "minio",
                    operation,
                    outcome,
                    java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        }
    }

    private <T> T minio(String operation, ThrowingSupplier<T> call) throws Exception {
        if (circuitBreaker == null) {
            return call.get();
        }
        return circuitBreaker.execute("minio", () -> {
            try {
                return call.get();
            } catch (Exception ex) {
                throw new MinioOperationException(ex);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {

        T get() throws Exception;
    }

    private static final class MinioOperationException extends RuntimeException {

        MinioOperationException(Exception cause) {
            super(cause);
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
