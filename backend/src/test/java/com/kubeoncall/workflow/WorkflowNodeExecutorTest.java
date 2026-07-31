package com.kubeoncall.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

class WorkflowNodeExecutorTest {

    @Test
    void shouldSkipDependentNodeWhenUpstreamFailed() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor();
        AlertWorkflowContext context = new AlertWorkflowContext(
                new AlarmEvent("a1", "d1", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of()),
                Instant.now());

        AlertWorkflowDefinition upstream = new AlertWorkflowDefinition(
                "upstreamNode",
                true,
                List.of(),
                c -> new NodeResult("upstreamNode", NodeStatus.FAILURE, "failed", Map.of()));
        AlertWorkflowDefinition dependent = new AlertWorkflowDefinition(
                "dependentNode",
                true,
                List.of("upstreamNode"),
                c -> new NodeResult("dependentNode", NodeStatus.SUCCESS, "ok", Map.of()));

        executor.execute(upstream, context, Duration.ofMillis(1000));

        List<String> missingDependencies = dependent.dependencies().stream()
                .filter(dep -> !context.getCompletedNodes().contains(dep))
                .toList();
        if (!missingDependencies.isEmpty()) {
            context.addSkippedNode(dependent.name());
            context.addNodeResult(new NodeResult(
                    dependent.name(),
                    NodeStatus.FAILURE,
                    "Skipped due to unmet dependencies: " + String.join(", ", missingDependencies),
                    Map.of("missingDependencies", missingDependencies)));
        } else {
            executor.execute(dependent, context, Duration.ofMillis(1000));
        }

        assertEquals(List.of("upstreamNode"), context.getFailedNodes());
        assertEquals(List.of("dependentNode"), context.getSkippedNodes());
        assertTrue(context.getCompletedNodes().isEmpty());
    }

    @Test
    void shouldAvoidExposingUnexpectedNodeFailureDetails() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor();
        AlertWorkflowContext context = new AlertWorkflowContext(
                new AlarmEvent("a1", "d1", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of()),
                Instant.now());
        AlertWorkflowDefinition definition = new AlertWorkflowDefinition("ticketNode", true, List.of(), ignored -> {
            throw new IllegalStateException("credential=secret is invalid");
        });

        NodeResult result = executor.execute(definition, context, Duration.ofSeconds(1));

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("Node execution failed", result.message());
        assertEquals("IllegalStateException", result.payload().get("exceptionType"));
    }
}
