package com.kubeoncall.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;

class KnowledgeObjectStorageServiceTest {

    @Test
    void shouldAvoidExposingMinioExceptionDetailsInStoredDocumentReference() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        doThrow(new IllegalStateException("credential=minioadmin is invalid"))
                .when(minioClient)
                .putObject(any(PutObjectArgs.class));
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getStorage().getMinio().setBucket("knowledge");
        KnowledgeObjectStorageService storageService = new KnowledgeObjectStorageService(minioClient, properties);

        StoredDocumentReference reference = storageService.store("runbook", "content", "manual");

        assertFalse(reference.stored());
        assertEquals("MinIO store failed", reference.message());
    }
}
