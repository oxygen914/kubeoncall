package com.kubeoncall.agent.planner;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;

@Component
public class PlannerTaskFactory {

    public Task create(
            String intent, TaskType taskType, String target, Map<String, Object> parameters, RiskLevel riskLevel) {
        return new Task(
                UUID.randomUUID().toString(),
                description(intent, taskType, target, parameters),
                taskType,
                riskLevel,
                target,
                parameters,
                new SopReference("SOP-" + taskType.name(), taskType.name() + " Standard Procedure", "v1", "rag:sop"));
    }

    private String description(String intent, TaskType taskType, String target, Map<String, Object> parameters) {
        return switch (taskType) {
            case QUERY_LOGS -> String.format("Query logs for %s to investigate %s", target, parameters.get("keyword"));
            case QUERY_METRICS ->
                String.format("Query metrics for %s over last %s minutes", target, parameters.get("windowMinutes"));
            case RESTART_SERVICE ->
                String.format("Prepare restart for %s with strategy %s", target, parameters.get("rolloutStrategy"));
            case SCALE_WORKLOAD -> String.format("Scale %s to %s replicas", target, parameters.get("replicas"));
            case PATCH_CONFIG ->
                String.format(
                        "Patch config %s=%s for %s",
                        parameters.get("configKey"), parameters.get("desiredValue"), target);
            case EXECUTE_SCRIPT -> String.format("Execute script %s on %s", parameters.get("scriptName"), target);
            case CLEAN_DATA -> String.format("Clean data for %s", target);
        };
    }
}
