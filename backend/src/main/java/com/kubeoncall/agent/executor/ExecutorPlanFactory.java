package com.kubeoncall.agent.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;

@Component
public class ExecutorPlanFactory {

    public String executorKind(TaskType taskType) {
        return switch (taskType) {
            case QUERY_LOGS, QUERY_METRICS, RESTART_SERVICE, SCALE_WORKLOAD, PATCH_CONFIG -> "kubernetes";
            case EXECUTE_SCRIPT -> "device";
            case CLEAN_DATA -> "database";
        };
    }

    public String action(TaskType taskType) {
        return switch (taskType) {
            case QUERY_LOGS -> "queryLogs";
            case QUERY_METRICS -> "queryMetricsContext";
            case RESTART_SERVICE -> "rolloutRestart";
            case SCALE_WORKLOAD -> "scaleWorkload";
            case PATCH_CONFIG -> "patchConfig";
            case EXECUTE_SCRIPT -> "executeScript";
            case CLEAN_DATA -> "cleanData";
        };
    }

    public List<String> missingParameters(Map<String, Object> parameters, List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String parameter : required) {
            if (!parameters.containsKey(parameter) || parameters.get(parameter) == null) {
                missing.add(parameter);
            }
        }
        return missing;
    }

    public void applyDefaults(Map<String, Object> parameters, List<String> missing) {
        for (String parameter : missing) {
            switch (parameter) {
                case "namespace" -> parameters.put("namespace", "default");
                case "lookbackMinutes" -> parameters.put("lookbackMinutes", 15);
                case "windowMinutes" -> parameters.put("windowMinutes", 10);
                case "rolloutStrategy" -> parameters.put("rolloutStrategy", "rolling");
                case "replicas" -> parameters.put("replicas", 3);
                case "readonly" -> parameters.put("readonly", true);
                case "keyword" -> parameters.put("keyword", "error");
                case "metricNames" -> parameters.put("metricNames", List.of("cpu_usage"));
                case "target" -> parameters.put("target", "default-scope");
                default -> {
                    // Parameters without a safe domain default remain missing.
                }
            }
        }
    }

    public void mergeSupplementalParameters(
            Map<String, Object> parameters, List<String> missing, Map<String, Object> supplemental) {
        for (String parameter : missing) {
            if (supplemental.containsKey(parameter) && supplemental.get(parameter) != null) {
                parameters.put(parameter, supplemental.get(parameter));
            }
        }
    }

    public Map<String, Object> supplementalSignals(GraphState state) {
        Object knowledge = state.getContext().get("plannerKnowledge");
        if (!(knowledge instanceof Map<?, ?> knowledgeMap)) {
            return Map.of();
        }
        return map(knowledgeMap.get("supplementalSignals"));
    }

    public List<String> toolWhitelist(GraphState state) {
        Object value = state.getContext().get("activatedSkillToolWhitelist");
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    public Map<String, String> supplementalSignalSources(GraphState state) {
        Map<String, String> sources = new LinkedHashMap<>();
        supplementalSignals(state).forEach((key, value) -> {
            if (!key.endsWith("Source") || value == null) {
                return;
            }
            String parameter =
                    switch (key) {
                        case "scaleHintSource" -> "replicas";
                        case "configHintSource" -> "configKey";
                        default -> null;
                    };
            if (parameter != null) {
                sources.put(parameter, String.valueOf(value));
            }
        });
        return sources;
    }

    public Map<String, String> parameterSources(
            Map<String, Object> parameters,
            int currentLoop,
            List<String> defaultedParameters,
            Map<String, String> supplementalSources) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String key : parameters.keySet()) {
            if (defaultedParameters.contains(key)) {
                sources.put(key, "default_applied_on_retry");
            } else if (supplementalSources.containsKey(key)) {
                sources.put(key, "tool:" + supplementalSources.get(key));
            } else if (currentLoop > 0
                    && (key.equals("namespace")
                            || key.equals("lookbackMinutes")
                            || key.equals("windowMinutes")
                            || key.equals("target"))) {
                sources.put(key, "default_applied_on_retry");
            } else {
                sources.put(key, "from_planner");
            }
        }
        return sources;
    }

    public String executionSummary(
            String executorKind,
            String action,
            String target,
            Map<String, Object> parameters,
            ToolDefinition toolDefinition) {
        return String.format(
                "Execute %s.%s via %s on target=%s with %d parameters",
                executorKind, action, toolDefinition.name(), target, parameters.size());
    }

    public Map<String, Object> payload(
            String executorKind,
            String action,
            Map<String, Object> parameters,
            boolean complete,
            ToolDefinition toolDefinition) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executorKind", executorKind);
        payload.put("action", action);
        payload.put("toolName", toolDefinition.name());
        payload.put("readOnly", toolDefinition.readOnly());
        payload.put("requiresApproval", toolDefinition.requiresApproval());
        payload.put("parameters", parameters);
        payload.put("complete", complete);
        payload.put("apiVersion", "v1");
        return payload;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
        return result;
    }
}
