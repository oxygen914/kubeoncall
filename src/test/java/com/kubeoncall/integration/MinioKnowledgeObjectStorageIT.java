package com.kubeoncall.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;

class MinioKnowledgeObjectStorageIT {

    private static final String BUCKET = "kubeoncall-it";

    private MinioClient minioClient;
    private KnowledgeObjectStorageService storageService;

    @BeforeEach
    void setUp() throws Exception {
        minioClient = MinioClient.builder()
                .endpoint("http://localhost:9000")
                .credentials("minioadmin", "minioadmin")
                .build();
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getStorage().getMinio().setBucket(BUCKET);
        storageService = new KnowledgeObjectStorageService(minioClient, properties);
    }

    @Test
    void shouldStoreAndReadKnowledgeSourceThroughRealMinio() throws Exception {
        String content = "Runbook source content";
        StoredDocumentReference reference = storageService.store("pod oom runbook", content, "runbook");

        assertTrue(reference.stored(), reference::message);
        assertEquals(BUCKET, reference.bucket());

        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(reference.bucket())
                .object(reference.objectKey())
                .build())) {
            assertEquals(content, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(reference.bucket())
                    .object(reference.objectKey())
                    .build());
        }
    }
}
