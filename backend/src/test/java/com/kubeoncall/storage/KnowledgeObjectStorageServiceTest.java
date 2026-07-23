package com.kubeoncall.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    @Test
    void recordsBoundedMinioErrorMetricAtTheActualClientBoundary() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        doThrow(new IllegalStateException("network unavailable"))
                .when(minioClient)
                .putObject(any(PutObjectArgs.class));
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getStorage().getMinio().setBucket("knowledge");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KnowledgeObjectStorageService storageService =
                new KnowledgeObjectStorageService(minioClient, properties, metrics(registry));

        storageService.store("runbook", "content", "manual");

        assertEquals(
                1.0,
                registry.get("kubeoncall.dependency.requests")
                        .tags("dependency", "minio", "operation", "put", "outcome", "error")
                        .counter()
                        .count());
        assertEquals(
                1L,
                registry.get("kubeoncall.dependency.latency_ms")
                        .tags("dependency", "minio", "operation", "put", "outcome", "error")
                        .summary()
                        .count());
    }

    @SuppressWarnings("unchecked")
    private static KubeOnCallMetricsService metrics(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new KubeOnCallMetricsService(provider);
    }
}
