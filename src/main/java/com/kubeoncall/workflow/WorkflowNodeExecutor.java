package com.kubeoncall.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

@Component
public class WorkflowNodeExecutor {

    public NodeResult execute(AlertWorkflowDefinition definition, AlertWorkflowContext context, Duration timeout) {
        String nodeName = definition.name();
        Instant nodeStartedAt = Instant.now();
        try {
            NodeResult result = definition.node().execute(context);
            long durationMs = Duration.between(nodeStartedAt, Instant.now()).toMillis();
            if (timeout != null && durationMs > timeout.toMillis()) {
                context.setDegraded(true);
                context.addFailedNode(nodeName);
                NodeResult timeoutResult = new NodeResult(
                        nodeName,
                        NodeStatus.FAILURE,
                        "Node execution timed out after " + durationMs + " ms",
                        Map.of("durationMs", durationMs, "timeoutMs", timeout.toMillis()));
                context.addNodeResult(enrichPayload(timeoutResult, context, durationMs));
                if (!definition.continueOnFailure()) {
                    context.terminate(nodeName);
                }
                return timeoutResult;
            }
            if (result.status() == NodeStatus.FAILURE) {
                context.setDegraded(true);
                context.addFailedNode(nodeName);
                if (!definition.continueOnFailure()) {
                    context.terminate(nodeName);
                }
            } else {
                context.addCompletedNode(nodeName);
            }
            context.addNodeResult(enrichPayload(result, context, durationMs));
            return result;
        } catch (Exception ex) {
            long durationMs = Duration.between(nodeStartedAt, Instant.now()).toMillis();
            context.setDegraded(true);
            context.addFailedNode(nodeName);
            if (!definition.continueOnFailure()) {
                context.terminate(nodeName);
            }
            NodeResult failure = new NodeResult(
                    nodeName,
                    NodeStatus.FAILURE,
                    "Node execution failed: " + ex.getMessage(),
                    Map.of("exceptionType", ex.getClass().getSimpleName()));
            context.addNodeResult(enrichPayload(failure, context, durationMs));
            return failure;
        }
    }

    private NodeResult enrichPayload(NodeResult result, AlertWorkflowContext context, long durationMs) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (result.payload() != null) {
            payload.putAll(result.payload());
        }
        payload.put("durationMs", durationMs);
        payload.put("degraded", context.isDegraded());
        payload.put("failedNodes", context.getFailedNodes());
        payload.put("skippedNodes", context.getSkippedNodes());
        payload.put("completedNodes", context.getCompletedNodes());
        payload.put("terminated", context.isTerminated());
        Object terminatedBy = context.getAttribute("workflowTerminatedBy");
        if (terminatedBy != null) {
            payload.put("workflowTerminatedBy", terminatedBy);
        }
        return new NodeResult(result.nodeName(), result.status(), result.message(), payload);
    }
}
