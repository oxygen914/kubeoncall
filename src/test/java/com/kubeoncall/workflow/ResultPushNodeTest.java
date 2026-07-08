package com.kubeoncall.workflow;

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
import com.kubeoncall.workflow.node.ResultPushNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResultPushNodeTest {

    @Test
    void shouldNotCreateSilenceByDefaultForP0() {
        ToolExecutor alertmanager = mock(ToolExecutor.class);
        when(alertmanager.getExecutorKind()).thenReturn("alertmanager");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "success");
        ok.put("httpStatus", 200);
        when(alertmanager.execute(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(ok);

        ResultPushNode node = new ResultPushNode(List.of(alertmanager), new KubeOnCallProperties());
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P0, false);

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        // createSilence must never be invoked by default.
        verify(alertmanager, never()).execute(org.mockito.ArgumentMatchers.eq("createSilence"), org.mockito.ArgumentMatchers.any());
        // The non-mutating alert event is the only alertmanager call.
        verify(alertmanager).execute(org.mockito.ArgumentMatchers.eq("sendAlertEvent"), org.mockito.ArgumentMatchers.any());
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
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        when(alertmanager.execute(anyString(), params.capture())).thenReturn(ok);

        ResultPushNode node = new ResultPushNode(List.of(alertmanager), new KubeOnCallProperties());
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P2, true);
        context.putAttribute("silenceApproved", true);

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        boolean silenceCalled = params.getAllValues().stream().anyMatch(p -> false);
        // createSilence path exercised: the summary should reflect a created silence.
        assertEquals(true, result.payload().get("silenceCreated"));
        // Silence action must have been called exactly once.
        verify(alertmanager).execute(org.mockito.ArgumentMatchers.eq("createSilence"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotCreateSilenceWhenPolicyOptsInButNotApproved() {
        ToolExecutor alertmanager = mock(ToolExecutor.class);
        when(alertmanager.getExecutorKind()).thenReturn("alertmanager");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "success");
        ok.put("httpStatus", 200);
        when(alertmanager.execute(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(ok);

        ResultPushNode node = new ResultPushNode(List.of(alertmanager), new KubeOnCallProperties());
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P2, true);
        // silenceApproved NOT set.

        node.execute(context);

        verify(alertmanager, never()).execute(org.mockito.ArgumentMatchers.eq("createSilence"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRecordStructuredSummaryEvenWithoutAlertmanager() {
        ResultPushNode node = new ResultPushNode(List.of(), new KubeOnCallProperties());
        AlertWorkflowContext context = contextWithPolicy(AlarmSeverity.P1, false);

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertTrue(result.payload().containsKey("fingerprint"));
        assertTrue(result.payload().containsKey("alertName"));
        assertEquals("host-high-cpu-p1", result.payload().get("policyId"));
        assertFalse(Boolean.TRUE.equals(result.payload().get("silenceCreated")));
    }

    private AlertWorkflowContext contextWithPolicy(AlarmSeverity severity, boolean autoSilence) {
        AlarmAction action = new AlarmAction("host-resource", "oncall", false, autoSilence, List.of());
        AlarmCondition condition = new AlarmCondition("HostHighCpuUsageP1", "host.cpu.usage_percent",
                AlarmResourceType.NODE, ">", 70.0, "10m", Map.of());
        AlarmPolicy policy = new AlarmPolicy("host-high-cpu-p1", "HostHighCpuUsageP1", "host",
                "host.cpu.usage_percent", AlarmResourceType.NODE, severity, condition,
                "cpu > 70", "15m", "cpu < 65", "runbook-host-cpu-high", "infra", action, Map.of());
        AlarmEvaluationResult evaluation = new AlarmEvaluationResult(
                true, policy, policy.id(), severity, 70.0, policy.runbookId(),
                policy.promql(), policy.window(), "host-resource", "matched", List.of());
        NormalizedAlarmEvent normalized = new NormalizedAlarmEvent(
                "a", "fp-cpu", "HostHighCpuUsageP1", "prometheus", "warning", null,
                AlarmResourceType.NODE, "node-a", "lab", "monitoring", "svc",
                "host.cpu.usage_percent", 72.0, 70.0, "%", "10m",
                Map.of("team", "infra"), Map.of(), "runbook-host-cpu-high", null, Instant.now(), "cpu", Map.of());
        AlarmEvent legacy = new AlarmEvent("a", "fp-cpu", "prometheus", severity.name(), "node-a", "cpu", Instant.now(), Map.of());
        return new AlertWorkflowContext(legacy, normalized, evaluation, Instant.now());
    }
}
