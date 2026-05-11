package com.kubeoncall.agent.verifier;

import com.kubeoncall.agent.node.ApprovalNode;
import com.kubeoncall.approval.ApprovalService;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.graph.PauseMetadata;
import com.kubeoncall.domain.task.Task;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VerifierApprovalNode extends ApprovalNode {

    private final ApprovalService approvalService;

    public VerifierApprovalNode(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Override
    public String getName() {
        return "verifierApprovalNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        String decision = String.valueOf(state.getContext().getOrDefault("verifierDecision", "UNKNOWN"));
        if (!"APPROVAL_REQUIRED".equals(decision)) {
            return new NodeResult(
                    getName(),
                    NodeStatus.SUCCESS,
                    "Approval not required",
                    Map.of("verifierDecision", decision)
            );
        }

        Task task = state.getCurrentTask();
        List<String> riskReasons = resolveRiskReasons(state);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskDescription", task == null ? null : task.description());
        snapshot.put("parameters", task == null ? Map.of() : task.parameters());
        state.setPauseMetadata(new PauseMetadata(
                "Awaiting human approval",
                getName(),
                Instant.now(),
                task == null ? null : task.taskId(),
                task == null || task.taskType() == null ? null : task.taskType().name(),
                task == null ? null : task.target(),
                task == null || task.riskLevel() == null ? null : task.riskLevel().name(),
                "ADMIN",
                riskReasons,
                snapshot
        ));
        state.addObservation("Execution paused for approval, taskId=" + (task == null ? "unknown" : task.taskId()) + ", reasons=" + riskReasons);
        ApprovalRequest request = approvalService.createPending(state, "system", "High risk operation pending approval");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", request.executionId());
        payload.put("taskId", request.taskId());
        payload.put("riskReasons", request.riskReasons());
        payload.put("verifierDecision", decision);
        return new NodeResult(
                getName(),
                NodeStatus.WAITING,
                "Approval created",
                payload
        );
    }

    private List<String> resolveRiskReasons(GraphState state) {
        Object value = state.getContext().get("verifierRiskReasons");
        if (value instanceof List<?> list) {
            List<String> reasons = list.stream()
                    .map(String::valueOf)
                    .filter(reason -> !reason.isBlank())
                    .toList();
            if (!reasons.isEmpty()) {
                return reasons;
            }
        }
        return List.of("High risk operation requires manual review");
    }
}
