package com.kubeoncall.workflow;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.node.StateCompareNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StateCompareNodeTest {

    @Test
    void shouldUsePolicyPromqlWhenAvailable() {
        ToolExecutor prometheus = mock(ToolExecutor.class);
        when(prometheus.getExecutorKind()).thenReturn("prometheus");
        Map<String, Object> toolResult = successResult();
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        when(prometheus.execute(anyString(), params.capture())).thenReturn(toolResult);

        StateCompareNode node = new StateCompareNode(List.of(prometheus), new KubeOnCallProperties());

        AlarmEvaluationResult evaluation = new AlarmEvaluationResult(
                true, null, "host-high-cpu-p0", com.kubeoncall.alarm.domain.AlarmSeverity.P0,
                85.0, "runbook-host-cpu-high",
                "100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))) > 85",
                "10m", "host-resource", "matched", List.of());
        NormalizedAlarmEvent normalized = normalized();
        AlertWorkflowContext context = new AlertWorkflowContext(legacy(), normalized, evaluation, Instant.now());

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        Map<String, Object> captured = params.getValue();
        assertEquals("100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))) > 85", captured.get("query"));
        assertEquals(10, captured.get("windowMinutes"));
        assertEquals("policy", result.payload().get("querySource"));
    }

    @Test
    void shouldFallBackToUpQueryWhenNoPolicyOrMetadata() {
        ToolExecutor prometheus = mock(ToolExecutor.class);
        when(prometheus.getExecutorKind()).thenReturn("prometheus");
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        when(prometheus.execute(anyString(), params.capture())).thenReturn(successResult());

        StateCompareNode node = new StateCompareNode(List.of(prometheus), new KubeOnCallProperties());
        AlertWorkflowContext context = new AlertWorkflowContext(
                new AlarmEvent("a", "d", "prom", "critical", "node-a", "cpu", Instant.now(), Map.of()),
                null, null, Instant.now());

        node.execute(context);

        assertEquals("up{instance=\"node-a\"}", params.getValue().get("query"));
    }

    @Test
    void shouldReturnFailureWhenPrometheusNotConfigured() {
        StateCompareNode node = new StateCompareNode(List.of(), new KubeOnCallProperties());
        AlertWorkflowContext context = new AlertWorkflowContext(
                new AlarmEvent("a", "d", "prom", "critical", "node-a", "cpu", Instant.now(), Map.of()),
                null, null, Instant.now());
        NodeResult result = node.execute(context);
        assertEquals(NodeStatus.FAILURE, result.status());
        assertTrue(result.message().contains("Prometheus"));
    }

    private static Map<String, Object> successResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "success");
        r.put("httpStatus", 200);
        return r;
    }

    private static AlarmEvent legacy() {
        return new AlarmEvent("a", "d", "prom", "critical", "node-a", "cpu", Instant.now(), Map.of());
    }

    private static NormalizedAlarmEvent normalized() {
        return new NormalizedAlarmEvent("a", "fp", "HostHighCpuUsageP0", "prometheus", "warning", null,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE, "node-a", "lab", "monitoring", "svc",
                "host.cpu.usage_percent", 85.1, 85.0, "%", "5m",
                Map.of(), Map.of(), "rb", null, Instant.now(), "cpu", Map.of());
    }

    // Reference to keep ToolDefinition import honest for future tool-whitelist assertions.
    @SuppressWarnings("unused")
    private ToolDefinition unused() {
        return null;
    }
}
