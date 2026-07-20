package com.kubeoncall.storage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

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
}
