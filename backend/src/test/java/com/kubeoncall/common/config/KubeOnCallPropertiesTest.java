package com.kubeoncall.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class KubeOnCallPropertiesTest {

    @Test
    void shouldBindExistingKubeOnCallKeysAcrossSplitConfigurationDomains() {
        Map<String, Object> source = Map.ofEntries(
                Map.entry("kubeoncall.agent.max-loops", "7"),
                Map.entry("kubeoncall.rag.embedding-dimensions", "768"),
                Map.entry("kubeoncall.storage.minio.bucket", "knowledge-test"),
                Map.entry("kubeoncall.alarm.p0-escalation-count", "5"),
                Map.entry("kubeoncall.memory.session-ttl-seconds", "120"),
                Map.entry("kubeoncall.cors.allowed-origins", "https://console.example.com"),
                Map.entry("kubeoncall.integrations.prometheus.endpoint", "http://prometheus:9090"));
        KubeOnCallProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("kubeoncall", Bindable.of(KubeOnCallProperties.class))
                .orElseThrow(() -> new AssertionError("kubeoncall properties should bind"));

        assertEquals(7, properties.getAgent().getMaxLoops());
        assertEquals(768, properties.getRag().getEmbeddingDimensions());
        assertEquals("knowledge-test", properties.getStorage().getMinio().getBucket());
        assertEquals(5, properties.getAlarm().getP0EscalationCount());
        assertEquals(120, properties.getMemory().getSessionTtlSeconds());
        assertEquals(
                List.of("https://console.example.com"), properties.getCors().getAllowedOrigins());
        assertEquals(
                "http://prometheus:9090",
                properties.getIntegrations().getPrometheus().getEndpoint());
    }

    @Test
    void shouldRetainConfigurationDefaults() {
        KubeOnCallProperties properties = new KubeOnCallProperties();

        assertTrue(properties.getAgent().isPlannerLlmEnabled());
        assertFalse(properties.getRag().isEmbeddingEnabled());
        assertEquals(168, properties.getAudit().getRetentionHours());
        assertEquals(3000, properties.getWorkflow().getNodeTimeoutMillis());
        assertEquals("Asia/Shanghai", properties.getMemory().getTemporalNormalizationZone());
        assertEquals(
                List.of("http://127.0.0.1:8081", "http://localhost:8081"),
                properties.getCors().getAllowedOrigins());
    }
}
