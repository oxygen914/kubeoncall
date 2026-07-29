package com.kubeoncall.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kubeoncall.agent.planner.PlannerDegradedReason;
import com.kubeoncall.agent.planner.PlannerLlmResult;
import com.kubeoncall.agent.planner.PlannerLlmService;
import com.kubeoncall.agent.planner.PlannerMode;
import com.kubeoncall.alarm.correlation.ChangeCorrelationService;
import com.kubeoncall.alarm.readmodel.AlarmQueryService;
import com.kubeoncall.monitoring.MonitoringViews.DataSources;
import com.kubeoncall.monitoring.MonitoringViews.Scope;
import com.kubeoncall.monitoring.MonitoringViews.Summary;

class MonitoringOperationsServiceTest {

    private MonitoringQueryService monitoring;
    private AlarmQueryService alarms;
    private PlannerLlmService plannerLlm;
    private MonitoringOperationsService service;

    @BeforeEach
    void setUp() {
        monitoring = mock(MonitoringQueryService.class);
        alarms = mock(AlarmQueryService.class);
        plannerLlm = mock(PlannerLlmService.class);
        service = new MonitoringOperationsService(monitoring, alarms, mock(ChangeCorrelationService.class), plannerLlm);
    }

    @Test
    void fallsBackToReadOnlyRulesWhenModelIsUnavailable() {
        Scope scope = new Scope("prod", "production", "payments");
        when(monitoring.summary("prod", "production", "payments"))
                .thenReturn(new Summary(
                        "prod",
                        3,
                        2,
                        1,
                        0,
                        8L,
                        Map.of("Running", 7L, "Pending", 1L),
                        82.5,
                        new DataSources(true, true),
                        Instant.parse("2026-07-29T10:00:00Z")));
        when(plannerLlm.planWithStatus(anyString(), anyMap()))
                .thenReturn(PlannerLlmResult.degraded(
                        PlannerMode.RULE_FALLBACK,
                        PlannerDegradedReason.RULE_MODE_CONFIGURED,
                        "openai-compatible",
                        "qwen-plus",
                        0));

        var result = service.advice(scope, Duration.ofHours(6), false);

        assertThat(result.generatedBy()).isEqualTo("RULE_FALLBACK");
        assertThat(result.modelAvailable()).isFalse();
        assertThat(result.safetyMode()).isEqualTo("READ_ONLY");
        assertThat(result.advice())
                .extracting(MonitoringViews.AdviceItem::id)
                .contains("node-health", "abnormal-pods", "high-cpu");
    }

    @Test
    void reportsUnavailableAlarmStoreWithoutInventingCorrelations() {
        when(alarms.isAvailable()).thenReturn(false);

        var result = service.correlations(new Scope("prod", null, null), Duration.ofHours(6), 10);

        assertThat(result.alarmDataAvailable()).isFalse();
        assertThat(result.changeDataAvailable()).isFalse();
        assertThat(result.correlations()).isEmpty();
    }
}
