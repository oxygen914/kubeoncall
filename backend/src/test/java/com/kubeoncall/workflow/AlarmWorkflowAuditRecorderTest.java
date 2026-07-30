package com.kubeoncall.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.service.ExecutionAuditService;

class AlarmWorkflowAuditRecorderTest {

    @Test
    void shouldRecordWorkflowAuditWithStructuredMetadata() {
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);
        AlarmWorkflowAuditRecorder recorder = new AlarmWorkflowAuditRecorder(executionAuditService);
        NormalizedAlarmEvent event = event();
        AlertWorkflowContext context = new AlertWorkflowContext(null, event, null, Instant.now());
        context.setDegraded(true);
        context.addFailedNode("diagnosis");
        context.putAttribute("skillDiagnosisPlan", Map.of("taskType", "QUERY_METRICS"));
        context.putAttribute("skillDiagnosisVerification", Map.of("status", "PARTIAL"));
        context.putAttribute("skillDiagnosisFallback", Map.of("used", true, "runbookId", "runbook-1"));
        context.putAttribute("skillDiagnosisInvokedTools", List.of("prometheus.rangeQuery"));
        context.putAttribute("skillDiagnosisAgentExecutionId", "agd_1");
        NodeResult result = new NodeResult("diagnosis", NodeStatus.FAILURE, "tool failed", Map.of());

        recorder.record(new AlarmWorkflowAuditRecorder.AuditRequest(
                "alarm-1",
                "DEGRADED",
                false,
                true,
                "workflow failed",
                "tool failed",
                List.of("prometheus"),
                Instant.now(),
                event,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "default policy"),
                activeState(),
                null,
                context,
                List.of(result),
                Map.of("suppressionReason", "none")));

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(executionAuditService)
                .recordAlarmExecution(
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        metadata.capture());

        assertEquals("fp-1", metadata.getValue().get("fingerprint"));
        assertEquals("node-a", metadata.getValue().get("resourceName"));
        assertEquals(2L, metadata.getValue().get("activeCount"));
        assertEquals(List.of("diagnosis"), metadata.getValue().get("nodeNames"));
        assertEquals("none", metadata.getValue().get("suppressionReason"));
        assertEquals(true, metadata.getValue().get("degraded"));
        assertEquals(Map.of("taskType", "QUERY_METRICS"), metadata.getValue().get("skillDiagnosisPlan"));
        assertEquals(Map.of("status", "PARTIAL"), metadata.getValue().get("skillDiagnosisVerification"));
        assertEquals(List.of("prometheus.rangeQuery"), metadata.getValue().get("skillDiagnosisInvokedTools"));
        assertEquals("agd_1", metadata.getValue().get("skillDiagnosisAgentExecutionId"));
    }

    @Test
    void shouldBuildConciseSummaryAndPayloadForActiveAlarm() {
        AlarmWorkflowAuditRecorder recorder = new AlarmWorkflowAuditRecorder(mock(ExecutionAuditService.class));
        String summary = recorder.summary(
                event(),
                AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "default policy"),
                activeState(),
                null,
                null,
                null,
                "completed");

        assertTrue(summary.contains("alert=HostHighCpu"));
        assertTrue(summary.contains("outcome=completed"));
        assertTrue(summary.contains("activeCount=2"));
        assertEquals(true, recorder.activeAlarmPayload(activeState()).get("present"));
        assertFalse((Boolean) recorder.activeAlarmPayload(null).get("present"));
    }

    private static ActiveAlarmState activeState() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new ActiveAlarmState(
                "fp-1",
                "alarm-1",
                "HostHighCpu",
                "cluster-a",
                "monitoring",
                "infra",
                "node-a",
                AlarmSeverity.P1,
                AlarmStatus.FIRING,
                "policy-1",
                now.minusSeconds(60),
                now,
                2);
    }

    private static NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "alarm-1",
                "fp-1",
                "HostHighCpu",
                "prometheus",
                "critical",
                AlarmSeverity.P1,
                AlarmResourceType.NODE,
                "node-a",
                "cluster-a",
                "monitoring",
                "infra",
                "host.cpu",
                92.0,
                80.0,
                "percent",
                "5m",
                Map.of(),
                Map.of(),
                "runbook-1",
                AlarmStatus.FIRING,
                Instant.parse("2026-07-13T00:00:00Z"),
                "CPU high",
                Map.of());
    }
}
