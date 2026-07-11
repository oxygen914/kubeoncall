package com.kubeoncall.agent.planner;

import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;

import java.util.List;
import java.util.Map;

public record PlannerLlmDecision(
        String intent,
        String confidence,
        String target,
        String targetSource,
        TaskType taskType,
        RiskLevel riskLevel,
        Map<String, Object> parameters,
        List<String> missingSignals,
        List<String> requestedSkills,
        String summary
) {
    public PlannerLlmDecision withRequestedSkills(List<String> skillIds) {
        return new PlannerLlmDecision(
                intent, confidence, target, targetSource, taskType, riskLevel,
                parameters, missingSignals, skillIds == null ? List.of() : List.copyOf(skillIds), summary);
    }
}
