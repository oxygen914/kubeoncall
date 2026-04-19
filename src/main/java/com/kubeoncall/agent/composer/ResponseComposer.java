package com.kubeoncall.agent.composer;

import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.PauseMetadata;
import com.kubeoncall.domain.task.PlannerSummary;
import com.kubeoncall.domain.task.Task;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResponseComposer {

    public String compose(GraphState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("executionId=").append(state.getExecutionId())
                .append("\nstatus=").append(state.getStatus())
                .append("\nrequestSummary=").append(buildRequestSummary(state))
                .append("\nplanSummary=").append(buildPlanSummary(state))
                .append("\nverifierSummary=").append(buildVerifierSummary(state))
                .append("\napprovalSummary=").append(buildApprovalSummary(state))
                .append("\nexecutionSummary=").append(buildExecutionSummary(state))
                .append("\ntoolSummary=").append(buildToolSummary(state))
                .append("\nauditSummary=").append(buildAuditSummary(state));
        return builder.toString();
    }

    private String buildRequestSummary(GraphState state) {
        PlannerSummary plannerSummary = getPlannerSummary(state);
        String intent = plannerSummary == null ? stringValue(state.getContext().get("plannerIntent")) : plannerSummary.intent();
        String confidence = plannerSummary == null ? stringValue(state.getContext().get("plannerConfidence")) : plannerSummary.confidence();
        return "question='" + state.getUserRequest() + "', intent=" + defaultString(intent, "unknown")
                + ", confidence=" + defaultString(confidence, "unknown");
    }

    private String buildPlanSummary(GraphState state) {
        Task task = state.getCurrentTask();
        PlannerSummary plannerSummary = getPlannerSummary(state);
        if (task == null) {
            return "no task plan";
        }
        String summary = plannerSummary == null ? null : plannerSummary.summary();
        String targetSource = plannerSummary == null ? null : plannerSummary.targetSource();
        List<String> missingSignals = plannerSummary == null ? List.of() : plannerSummary.missingSignals();
        boolean approvalRequired = state.getTaskPlan() != null && state.getTaskPlan().approvalRequired();
        return "taskId=" + task.taskId()
                + ", type=" + task.taskType()
                + ", target=" + task.target()
                + ", risk=" + task.riskLevel()
                + ", approvalRequired=" + approvalRequired
                + ", targetSource=" + defaultString(targetSource, "unknown")
                + ", summary=" + defaultString(summary, task.description())
                + ", missingSignals=" + missingSignals;
    }

    private String buildVerifierSummary(GraphState state) {
        String verifierDecision = stringValue(state.getContext().get("verifierDecision"));
        Object reasons = state.getContext().get("verifierRiskReasons");
        return "decision=" + defaultString(verifierDecision, "not_available")
                + ", reasons=" + (reasons == null ? List.of() : reasons);
    }

    private String buildApprovalSummary(GraphState state) {
        PauseMetadata pauseMetadata = state.getPauseMetadata();
        if (pauseMetadata == null && state.getFinalApprovalDecision() == null) {
            return "approvalNotRequiredOrNotTriggered";
        }
        StringBuilder builder = new StringBuilder();
        if (pauseMetadata != null) {
            builder.append("pending={reason=").append(pauseMetadata.reason())
                    .append(", waitingNode=").append(pauseMetadata.waitingNode())
                    .append(", requiredRole=").append(pauseMetadata.requiredRole())
                    .append(", riskReasons=").append(pauseMetadata.riskReasons())
                    .append("}");
        }
        if (state.getFinalApprovalDecision() != null) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append("finalDecision=").append(state.getFinalApprovalDecision())
                    .append(", resumeAttempts=").append(state.getResumeAttempts());
        }
        return builder.toString();
    }

    private String buildExecutionSummary(GraphState state) {
        ExecutionPlan executionPlan = getExecutionPlan(state);
        NodeResult latest = latestNodeResult(state);
        if (executionPlan == null) {
            return "executionPlanNotBuilt, latestNode=" + (latest == null ? "none" : latest.nodeName() + "/" + latest.status());
        }
        return "executorKind=" + executionPlan.executorKind()
                + ", action=" + executionPlan.action()
                + ", missingParameters=" + executionPlan.missingParameters()
                + ", parameterSources=" + executionPlan.parameterSources()
                + ", summary=" + executionPlan.executionSummary()
                + ", retryHint=" + defaultString(executionPlan.retryHint(), "none")
                + ", latestNode=" + (latest == null ? "none" : latest.nodeName() + "/" + latest.status());
    }

    private String buildToolSummary(GraphState state) {
        PlannerSummary plannerSummary = getPlannerSummary(state);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("plannerAvailable", readToolNames(state.getContext().get("plannerAvailableTools")));
        summary.put("plannerConsulted", plannerSummary == null ? List.of() : plannerSummary.consultedTools());
        summary.put("plannerEvidence", plannerSummary == null ? Map.of() : plannerSummary.evidenceSources());
        summary.put("verifierTool", defaultString(stringValue(state.getContext().get("verifierTool")), "not_evaluated"));
        summary.put("executorTool", defaultString(readExecutorToolName(state), "not_selected"));
        summary.put("executorResultStatus", readExecutorResultStatus(state));
        return summary.toString();
    }

    private String buildAuditSummary(GraphState state) {
        return "approvalAuditTrail=" + state.getApprovalAuditTrail()
                + ", observations=" + state.getObservations();
    }

    private PlannerSummary getPlannerSummary(GraphState state) {
        Object value = state.getContext().get("plannerSummary");
        return value instanceof PlannerSummary summary ? summary : null;
    }

    private ExecutionPlan getExecutionPlan(GraphState state) {
        Object value = state.getContext().get("executionPlan");
        return value instanceof ExecutionPlan plan ? plan : null;
    }

    private NodeResult latestNodeResult(GraphState state) {
        List<NodeResult> results = state.getNodeResults();
        return results.isEmpty() ? null : results.get(results.size() - 1);
    }

    private List<String> readToolNames(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(tool -> String.valueOf(tool.get("name")))
                    .toList();
        }
        return List.of();
    }

    private String readExecutorToolName(GraphState state) {
        Object value = state.getContext().get("executorPayload");
        if (value instanceof Map<?, ?> map) {
            Object toolName = map.get("toolName");
            return toolName == null ? null : String.valueOf(toolName);
        }
        return null;
    }

    private String readExecutorResultStatus(GraphState state) {
        Object value = state.getContext().get("executorResult");
        if (value instanceof Map<?, ?> map) {
            Object status = map.get("status");
            return status == null ? "not_executed" : String.valueOf(status);
        }
        return "not_executed";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
