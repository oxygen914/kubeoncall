package com.kubeoncall.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class KubeOnCallMetricsServiceTest {

    @Test
    void shouldRecordAlarmSilenceApprovalMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        KubeOnCallMetricsService metricsService = new KubeOnCallMetricsService(provider);

        metricsService.recordAlarmSilenceApproval("approved");

        assertEquals(
                1.0,
                registry.counter("kubeoncall.alarm.silence_approvals", "outcome", "approved")
                        .count());
    }

    @Test
    void shouldRecordAlarmAcknowledgementMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        KubeOnCallMetricsService metricsService = new KubeOnCallMetricsService(provider);

        metricsService.recordAlarmAcknowledgement("acknowledged");

        assertEquals(
                1.0,
                registry.counter("kubeoncall.alarm.acknowledgements", "outcome", "acknowledged")
                        .count());
    }

    @Test
    void shouldRecordAlarmRecoveryMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        KubeOnCallMetricsService metricsService = new KubeOnCallMetricsService(provider);

        metricsService.recordAlarmRecovery("confirmed", "P1");

        assertEquals(
                1.0,
                registry.counter("kubeoncall.alarm.recoveries", "outcome", "confirmed", "severity", "p1")
                        .count());
    }

    @Test
    void shouldRecordKnowledgeAndRagFallbackMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        KubeOnCallMetricsService metricsService = new KubeOnCallMetricsService(provider);

        metricsService.recordKnowledge("ingest", "success", 1);
        metricsService.recordRagRetrieval("hybrid", "es", false, 0, 5);
        metricsService.recordRagRerank(true, false, true);

        assertEquals(
                1.0,
                registry.counter("kubeoncall.knowledge.operations", "operation", "ingest", "outcome", "success")
                        .count());
        assertEquals(
                1.0,
                registry.counter("kubeoncall.rag.empty_results", "method", "hybrid")
                        .count());
        assertEquals(
                1.0,
                registry.counter(
                                "kubeoncall.rag.reranks",
                                "cross_encoder_enabled",
                                "true",
                                "applied",
                                "false",
                                "fallback",
                                "true")
                        .count());
    }

    @Test
    void shouldUseEmptyRegistryWhenMetricsRegistryIsUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        KubeOnCallMetricsService metricsService = new KubeOnCallMetricsService(provider);

        metricsService.recordGraphExecution("ask", "success", false, false);
    }
}
