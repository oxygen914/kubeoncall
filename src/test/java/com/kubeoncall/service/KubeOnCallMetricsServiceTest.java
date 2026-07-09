package com.kubeoncall.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KubeOnCallMetricsServiceTest {

    @Test
    void shouldRecordAlarmSilenceApprovalMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        KubeOnCallMetricsService metricsService = new KubeOnCallMetricsService(provider);

        metricsService.recordAlarmSilenceApproval("approved");

        assertEquals(1.0, registry.counter(
                "kubeoncall.alarm.silence_approvals",
                "outcome", "approved"
        ).count());
    }
}
