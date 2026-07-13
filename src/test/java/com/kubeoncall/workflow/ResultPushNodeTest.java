package com.kubeoncall.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmAction;
import com.kubeoncall.alarm.domain.AlarmCondition;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.node.NotificationNode;
import com.kubeoncall.workflow.node.ResultPushNode;
import com.kubeoncall.workflow.node.SilenceNode;
import com.kubeoncall.workflow.node.TicketNode;

class ResultPushNodeTest {

    @Test
    void shouldNotCreateSilenceByDefaultForP0() {
        ToolExecutor alertmanager = mock(ToolExecutor.class);
        when(alertmanager.getExecutorKind()).thenReturn("alertmanager");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "success");
        ok.put("httpStatus", 200);
        when(alertmanager.execute(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ok);

        ResultPushNode node = new ResultPushNode(List.of(alertmanager), new KubeOnCallProperties());
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P0, false);

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        verify(alertmanager, never()).execute(anyString(), any());
        assertEquals(false, result.payload().get("silenceCreated"));
        assertEquals("P0", result.payload().get("severity"));
    }

    @Test
    void shouldOnlyCreateSilenceWhenPolicyOptsInAndApproved() {
        ToolExecutor alertmanager = mock(ToolExecutor.class);
        when(alertmanager.getExecutorKind()).thenReturn("alertmanager");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "success");
        ok.put("httpStatus", 200);
        when(alertmanager.execute(eq("createSilence"), any())).thenReturn(ok);

        SilenceNode node = new SilenceNode(List.of(alertmanager));
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P2, true);
        context.putAttribute("silenceApproved", true);
        context.putAttribute("resultSummary", Map.of("fingerprint", "fp-cpu"));

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals(true, result.payload().get("silenceCreated"));
        verify(alertmanager).execute(eq("createSilence"), any());
    }

    @Test
    void shouldNotCreateSilenceWhenPolicyOptsInButNotApproved() {
        ToolExecutor alertmanager = mock(ToolExecutor.class);
        when(alertmanager.getExecutorKind()).thenReturn("alertmanager");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "success");
        ok.put("httpStatus", 200);
        when(alertmanager.execute(anyString(), any())).thenReturn(ok);

        SilenceNode node = new SilenceNode(List.of(alertmanager));
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P2, true);
        // silenceApproved NOT set.

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals(true, result.payload().get("skipped"));
        verify(alertmanager, never()).execute(eq("createSilence"), any());
    }

    @Test
    void shouldRecordStructuredSummaryEvenWithoutAlertmanager() {
        ResultPushNode node = new ResultPushNode(List.of(), new KubeOnCallProperties());
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P1, false);
        context.putAttribute("diagnosis", Map.of("strategy", "CURRENT_EVIDENCE_ONLY"));

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertTrue(result.payload().containsKey("fingerprint"));
        assertTrue(result.payload().containsKey("alertName"));
        assertEquals("host-high-cpu-p1", result.payload().get("policyId"));
        assertEquals(
                Map.of("strategy", "CURRENT_EVIDENCE_ONLY"), result.payload().get("diagnosis"));
        assertFalse(Boolean.TRUE.equals(result.payload().get("silenceCreated")));
    }

    @Test
    void notificationNodeShouldSendSafeAlertEventOnly() {
        ToolExecutor alertmanager = mock(ToolExecutor.class);
        when(alertmanager.getExecutorKind()).thenReturn("alertmanager");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "success");
        ok.put("httpStatus", 200);
        when(alertmanager.execute(eq("sendAlertEvent"), any())).thenReturn(ok);
        NotificationNode node = new NotificationNode(List.of(alertmanager));
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P1, false);
        context.putAttribute("resultSummary", Map.of("fingerprint", "fp-cpu"));

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        verify(alertmanager).execute(eq("sendAlertEvent"), any());
        verify(alertmanager, never()).execute(eq("createSilence"), any());
    }

    @Test
    void ticketNodeShouldCreateHighPriorityIncident() {
        ToolExecutor incident = mock(ToolExecutor.class);
        when(incident.getExecutorKind()).thenReturn("incident");
        when(incident.execute(eq("createOrUpdateIncident"), any()))
                .thenReturn(Map.of(
                        "status", "success",
                        "httpStatus", 201,
                        "incidentId", "INC-1001"));
        TicketNode node = new TicketNode(List.of(incident));
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P1, false);
        context.putAttribute("resultSummary", Map.of("fingerprint", "fp-cpu"));

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("created", result.payload().get("status"));
        assertEquals("createOrUpdateIncident", result.payload().get("action"));
        assertEquals(result.payload(), context.getAttribute("ticket"));
        verify(incident).execute(eq("createOrUpdateIncident"), any());
    }

    private AlertWorkflowContext contextWithPolicy(AlarmSeverity severity, boolean autoSilence) {
        AlarmAction action = new AlarmAction("host-resource", "oncall", false, autoSilence, List.of());
        AlarmCondition condition = new AlarmCondition(
                "HostHighCpuUsageP1", "host.cpu.usage_percent", AlarmResourceType.NODE, ">", 70.0, "10m", Map.of());
        AlarmPolicy policy = new AlarmPolicy(
                "host-high-cpu-p1",
                "HostHighCpuUsageP1",
                "host",
                "host.cpu.usage_percent",
                AlarmResourceType.NODE,
                severity,
                condition,
                "cpu > 70",
                "15m",
                "cpu < 65",
                "runbook-host-cpu-high",
                "infra",
                action,
                Map.of());
        AlarmEvaluationResult evaluation = new AlarmEvaluationResult(
                true,
                policy,
                policy.id(),
                severity,
                70.0,
                policy.runbookId(),
                policy.promql(),
                policy.window(),
                "host-resource",
                "matched",
                List.of());
        NormalizedAlarmEvent normalized = new NormalizedAlarmEvent(
                "a",
                "fp-cpu",
                "HostHighCpuUsageP1",
                "prometheus",
                "warning",
                null,
                AlarmResourceType.NODE,
                "node-a",
                "lab",
                "monitoring",
                "svc",
                "host.cpu.usage_percent",
                72.0,
                70.0,
                "%",
                "10m",
                Map.of("team", "infra"),
                Map.of(),
                "runbook-host-cpu-high",
                null,
                Instant.now(),
                "cpu",
                Map.of());
        AlarmEvent legacy =
                new AlarmEvent("a", "fp-cpu", "prometheus", severity.name(), "node-a", "cpu", Instant.now(), Map.of());
        return new AlertWorkflowContext(legacy, normalized, evaluation, Instant.now());
    }
}
