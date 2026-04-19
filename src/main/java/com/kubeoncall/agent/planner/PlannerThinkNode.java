package com.kubeoncall.agent.planner;

import com.kubeoncall.agent.node.ThinkNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.PlannerSummary;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskPlan;
import com.kubeoncall.domain.task.TaskType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PlannerThinkNode extends ThinkNode {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("(\\w+[-_]?\\w*)-?(service|gateway|api|worker|job)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    @Override
    public String getName() {
        return "plannerThinkNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        String executionId = state.getExecutionId() == null ? UUID.randomUUID().toString() : state.getExecutionId();
        state.setExecutionId(executionId);

        String userRequest = state.getUserRequest();
        String normalized = normalizeRequest(userRequest);
        String intent = inferIntent(normalized);
        String confidence = inferConfidence(normalized, intent);
        String target = inferTarget(normalized);
        String targetSource = inferTargetSource(normalized, target);
        TaskType taskType = mapIntentToTaskType(intent);
        Map<String, Object> plannerKnowledge = getPlannerKnowledge(state);
        Map<String, Object> parameters = inferParameters(normalized, taskType, target, plannerKnowledge);
        Map<String, String> parameterSources = buildParameterSources(parameters, normalized, plannerKnowledge);
        RiskLevel riskLevel = inferRiskLevel(intent, taskType, target, parameters, plannerKnowledge);
        List<String> missingSignals = identifyMissingSignals(normalized, taskType, parameters, plannerKnowledge);
        List<String> consultedTools = consultedTools(state);
        Map<String, String> evidenceSources = buildEvidenceSources(plannerKnowledge);
        String planSummary = buildPlanSummary(intent, taskType, target, riskLevel, consultedTools);

        PlannerSummary summary = new PlannerSummary(
                normalized,
                intent,
                confidence,
                target,
                targetSource,
                parameterSources,
                missingSignals,
                planSummary,
                consultedTools,
                evidenceSources
        );

        state.getContext().put("plannerSummary", summary);
        state.getContext().put("plannerIntent", intent);
        state.getContext().put("plannerConfidence", confidence);

        Task task = new Task(
                UUID.randomUUID().toString(),
                buildTaskDescription(intent, taskType, target, parameters),
                taskType,
                riskLevel,
                target,
                parameters,
                new SopReference("SOP-" + taskType.name(), taskType.name() + " Standard Procedure", "v1", "rag:sop")
        );

        TaskPlan taskPlan = new TaskPlan(
                executionId,
                userRequest,
                List.of(task),
                Instant.now(),
                riskLevel.ordinal() >= RiskLevel.HIGH.ordinal()
        );

        state.setTaskPlan(taskPlan);
        state.setCurrentTask(task);
        state.addObservation("Planner: intent=" + intent + ", confidence=" + confidence + ", target=" + target
                + ", risk=" + riskLevel + ", consultedTools=" + consultedTools);

        return new NodeResult(
                getName(),
                NodeStatus.SUCCESS,
                "Planner produced task plan",
                Map.of(
                        "taskCount", taskPlan.tasks().size(),
                        "intent", intent,
                        "confidence", confidence,
                        "consultedTools", consultedTools,
                        "evidenceSources", evidenceSources
                )
        );
    }

    private String normalizeRequest(String request) {
        if (request == null) return "";
        return request.trim().replaceAll("\\s+", " ");
    }

    private String inferIntent(String normalized) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("查") || lower.contains("看") || lower.contains("日志") || lower.contains("log")) {
            return "QUERY_LOGS";
        }
        if (lower.contains("指标") || lower.contains("监控") || lower.contains("metric") || lower.contains("cpu") || lower.contains("memory")) {
            return "QUERY_METRICS";
        }
        if (lower.contains("重启") || lower.contains("restart")) {
            return "RESTART_SERVICE";
        }
        if (lower.contains("扩容") || lower.contains("缩容") || lower.contains("scale") || lower.contains("副本")) {
            return "SCALE_WORKLOAD";
        }
        if (lower.contains("配置") || lower.contains("超时") || lower.contains("timeout") || lower.contains("config") || lower.contains("调整")) {
            return "PATCH_CONFIG";
        }
        if (lower.contains("脚本") || lower.contains("script") || lower.contains("执行")) {
            return "EXECUTE_SCRIPT";
        }
        if (lower.contains("清理") || lower.contains("删除") || lower.contains("clean") || lower.contains("delete")) {
            return "CLEAN_DATA";
        }
        return "QUERY_LOGS";
    }

    private String inferConfidence(String normalized, String intent) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        int matchCount = 0;
        if (intent.equals("RESTART_SERVICE") && (lower.contains("重启") || lower.contains("restart"))) matchCount++;
        if (intent.equals("QUERY_LOGS") && (lower.contains("日志") || lower.contains("log"))) matchCount++;
        if (intent.equals("SCALE_WORKLOAD") && (lower.contains("扩容") || lower.contains("scale"))) matchCount++;
        if (intent.equals("PATCH_CONFIG") && (lower.contains("配置") || lower.contains("config") || lower.contains("超时"))) matchCount++;
        return matchCount > 0 ? "HIGH" : "MEDIUM";
    }

    private String inferTarget(String normalized) {
        Matcher matcher = SERVICE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(0);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("payment")) return "payment-service";
        if (lower.contains("gateway")) return "gateway-service";
        if (lower.contains("order")) return "order-service";
        if (lower.contains("user")) return "user-service";
        return "unknown-service";
    }

    private String inferTargetSource(String normalized, String target) {
        if (SERVICE_PATTERN.matcher(normalized).find()) {
            return "extracted_from_request";
        }
        if (!target.equals("unknown-service")) {
            return "inferred_from_keywords";
        }
        return "default_fallback";
    }

    private TaskType mapIntentToTaskType(String intent) {
        return switch (intent) {
            case "QUERY_LOGS" -> TaskType.QUERY_LOGS;
            case "QUERY_METRICS" -> TaskType.QUERY_METRICS;
            case "RESTART_SERVICE" -> TaskType.RESTART_SERVICE;
            case "SCALE_WORKLOAD" -> TaskType.SCALE_WORKLOAD;
            case "PATCH_CONFIG" -> TaskType.PATCH_CONFIG;
            case "EXECUTE_SCRIPT" -> TaskType.EXECUTE_SCRIPT;
            case "CLEAN_DATA" -> TaskType.CLEAN_DATA;
            default -> TaskType.QUERY_LOGS;
        };
    }

    private Map<String, Object> inferParameters(String normalized, TaskType taskType, String target, Map<String, Object> plannerKnowledge) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", readServiceMetadata(plannerKnowledge).getOrDefault("namespace", "default"));

        switch (taskType) {
            case QUERY_LOGS -> {
                params.put("keyword", extractKeyword(normalized));
                params.put("lookbackMinutes", 15);
            }
            case QUERY_METRICS -> {
                params.put("metricNames", List.of("cpu_usage", "memory_usage"));
                params.put("windowMinutes", readMetricWindow(plannerKnowledge));
            }
            case PATCH_CONFIG -> {
                params.put("configKey", extractConfigKey(normalized));
                params.put("desiredValue", extractConfigValue(normalized));
                params.put("changeReason", "operator_request");
            }
            case RESTART_SERVICE -> {
                params.put("rolloutStrategy", "rolling");
                params.put("reason", "operator_initiated_restart");
            }
            case SCALE_WORKLOAD -> {
                params.put("replicas", extractReplicas(normalized));
                params.put("reason", "manual_scaling");
            }
            case EXECUTE_SCRIPT -> {
                params.put("scriptName", "diagnostic.sh");
                params.put("args", List.of());
                params.put("readonly", true);
            }
            case CLEAN_DATA -> params.put("target", target);
        }
        return params;
    }

    private String extractKeyword(String normalized) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("超时") || lower.contains("timeout")) return "timeout";
        if (lower.contains("错误") || lower.contains("error")) return "error";
        if (lower.contains("异常") || lower.contains("exception")) return "exception";
        return "error";
    }

    private String extractConfigKey(String normalized) {
        if (normalized.contains("超时") || normalized.contains("timeout")) return "timeout";
        return "config_key";
    }

    private String extractConfigValue(String normalized) {
        Matcher matcher = NUMBER_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group() + "s";
        }
        return "60s";
    }

    private int extractReplicas(String normalized) {
        Matcher matcher = NUMBER_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        return 3;
    }

    private Map<String, String> buildParameterSources(Map<String, Object> parameters, String normalized, Map<String, Object> plannerKnowledge) {
        Map<String, String> sources = new LinkedHashMap<>();
        boolean hasMetricContext = plannerKnowledge.containsKey("metricsContext");
        for (String key : parameters.keySet()) {
            if (key.equals("namespace") && readServiceMetadata(plannerKnowledge).containsKey("namespace")) {
                sources.put(key, "tool:cmdb.getServiceMetadata");
            } else if (key.equals("windowMinutes") && hasMetricContext) {
                sources.put(key, "tool:prometheus.queryRange");
            } else if (NUMBER_PATTERN.matcher(normalized).find() && (key.equals("replicas") || key.contains("Value"))) {
                sources.put(key, "extracted_from_request");
            } else {
                sources.put(key, "inferred_from_intent");
            }
        }
        return sources;
    }

    private RiskLevel inferRiskLevel(String intent, TaskType taskType, String target, Map<String, Object> parameters, Map<String, Object> plannerKnowledge) {
        String criticality = String.valueOf(readServiceMetadata(plannerKnowledge).getOrDefault("criticality", "medium"));
        String environment = String.valueOf(readServiceMetadata(plannerKnowledge).getOrDefault("environment", "lab"));
        if (taskType == TaskType.CLEAN_DATA || taskType == TaskType.EXECUTE_SCRIPT) {
            return RiskLevel.CRITICAL;
        }
        if ("high".equalsIgnoreCase(criticality) && !taskType.name().startsWith("QUERY")) {
            return RiskLevel.HIGH;
        }
        if ("production".equalsIgnoreCase(environment) && !taskType.name().startsWith("QUERY")) {
            return RiskLevel.HIGH;
        }
        if (taskType == TaskType.RESTART_SERVICE) {
            if (target.contains("payment") || target.contains("order") || target.contains("core")) {
                return RiskLevel.HIGH;
            }
            return RiskLevel.MEDIUM;
        }
        if (taskType == TaskType.PATCH_CONFIG || taskType == TaskType.SCALE_WORKLOAD) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private List<String> identifyMissingSignals(String normalized, TaskType taskType, Map<String, Object> parameters, Map<String, Object> plannerKnowledge) {
        List<String> missing = new ArrayList<>();
        if (taskType == TaskType.PATCH_CONFIG && parameters.get("configKey").equals("config_key")) {
            missing.add("specific_config_key_not_identified");
        }
        if (taskType == TaskType.SCALE_WORKLOAD && !NUMBER_PATTERN.matcher(normalized).find()) {
            missing.add("target_replica_count_not_specified");
        }
        if (!plannerKnowledge.containsKey("sop")) {
            missing.add("sop_context_unavailable");
        }
        if (!plannerKnowledge.containsKey("serviceMetadata")) {
            missing.add("service_metadata_unavailable");
        }
        return missing;
    }

    private String buildPlanSummary(String intent, TaskType taskType, String target, RiskLevel riskLevel, List<String> consultedTools) {
        return String.format("Intent=%s, TaskType=%s, Target=%s, Risk=%s, ConsultedTools=%s", intent, taskType, target, riskLevel, consultedTools);
    }

    private String buildTaskDescription(String intent, TaskType taskType, String target, Map<String, Object> parameters) {
        return switch (taskType) {
            case QUERY_LOGS -> String.format("Query logs for %s to investigate %s", target, parameters.get("keyword"));
            case QUERY_METRICS -> String.format("Query metrics for %s over last %s minutes", target, parameters.get("windowMinutes"));
            case RESTART_SERVICE -> String.format("Prepare restart for %s with strategy %s", target, parameters.get("rolloutStrategy"));
            case SCALE_WORKLOAD -> String.format("Scale %s to %s replicas", target, parameters.get("replicas"));
            case PATCH_CONFIG -> String.format("Patch config %s=%s for %s", parameters.get("configKey"), parameters.get("desiredValue"), target);
            case EXECUTE_SCRIPT -> String.format("Execute script %s on %s", parameters.get("scriptName"), target);
            case CLEAN_DATA -> String.format("Clean data for %s", target);
        };
    }

    private Map<String, Object> getPlannerKnowledge(GraphState state) {
        Object value = state.getContext().get("plannerKnowledge");
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> casted = new LinkedHashMap<>();
            raw.forEach((key, entryValue) -> casted.put(String.valueOf(key), entryValue));
            return casted;
        }
        return Map.of();
    }

    private List<String> consultedTools(GraphState state) {
        Object value = state.getContext().get("plannerAvailableTools");
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(tool -> String.valueOf(tool.get("name")))
                    .toList();
        }
        return List.of();
    }

    private Map<String, String> buildEvidenceSources(Map<String, Object> plannerKnowledge) {
        Map<String, String> evidence = new LinkedHashMap<>();
        plannerKnowledge.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map && map.containsKey("tool")) {
                evidence.put(key, String.valueOf(map.get("tool")));
            }
        });
        return evidence;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readServiceMetadata(Map<String, Object> plannerKnowledge) {
        Object value = plannerKnowledge.get("serviceMetadata");
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
            return result;
        }
        return Map.of();
    }

    private int readMetricWindow(Map<String, Object> plannerKnowledge) {
        Object value = plannerKnowledge.get("metricsContext");
        if (value instanceof Map<?, ?> raw) {
            Object window = raw.get("windowMinutes");
            if (window instanceof Number number) {
                return number.intValue();
            }
        }
        return 10;
    }
}
