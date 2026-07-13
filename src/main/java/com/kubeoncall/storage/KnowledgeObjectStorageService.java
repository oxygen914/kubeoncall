package com.kubeoncall.storage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;

@Service
public class KnowledgeObjectStorageService {

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
            return new StoredDocumentReference(objectKey, bucket, false, "MinIO store failed: " + ex.getMessage());
        }
    }
}
