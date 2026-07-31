package com.kubeoncall.alarm.escalation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;

class AlarmEscalationServiceTest {

    @Test
    void shouldDeliverEscalationToNotificationAndIncidentSystems() {
        ToolExecutor alertmanager = executor("alertmanager");
        ToolExecutor incident = executor("incident");
        when(alertmanager.execute(eq("sendAlertEvent"), any()))
                .thenReturn(Map.of("status", "success", "httpStatus", 200));
        when(incident.execute(eq("escalateIncident"), any()))
                .thenReturn(Map.of("status", "success", "httpStatus", 200));
        AlarmEscalationService service = new AlarmEscalationService(List.of(alertmanager, incident));

        NodeResult result = service.escalate(event(), evaluation(), 3, 2);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("delivered", result.payload().get("deliveryStatus"));
        verify(alertmanager).execute(eq("sendAlertEvent"), any());
        verify(incident).execute(eq("escalateIncident"), any());
    }

    @Test
    void shouldFailEscalationWhenEitherDeliveryFails() {
        ToolExecutor alertmanager = executor("alertmanager");
        ToolExecutor incident = executor("incident");
        when(alertmanager.execute(eq("sendAlertEvent"), any()))
                .thenReturn(Map.of("status", "success", "httpStatus", 200));
        when(incident.execute(eq("escalateIncident"), any())).thenReturn(Map.of("status", "failed", "httpStatus", 503));
        AlarmEscalationService service = new AlarmEscalationService(List.of(alertmanager, incident));

        NodeResult result = service.escalate(event(), evaluation(), 3, 2);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("failed", result.payload().get("deliveryStatus"));
    }

    private static ToolExecutor executor(String kind) {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getExecutorKind()).thenReturn(kind);
        return executor;
    }

    private static NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "alarm-1",
                "fp-escalate",
                "HostHighCpuUsageP0",
                "prometheus",
                "critical",
                AlarmSeverity.P0,
                null,
                "node-a",
                "cluster-a",
                "prod",
                "infra",
                "host.cpu.usage_percent",
                91.0,
                85.0,
                "%",
                "5m",
                Map.of(),
                Map.of(),
                "runbook-host-cpu-high",
                null,
                Instant.now(),
                "cpu critical",
                Map.of());
    }

    private static AlarmEvaluationResult evaluation() {
        return new AlarmEvaluationResult(
                true,
                null,
                "host-high-cpu-p0",
                AlarmSeverity.P0,
                85.0,
                "runbook-host-cpu-high",
                "cpu > 85",
                "10m",
                "host-resource",
                "matched",
                List.of());
    }
}
