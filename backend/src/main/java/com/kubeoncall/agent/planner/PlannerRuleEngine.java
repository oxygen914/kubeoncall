package com.kubeoncall.agent.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.k8s.KubernetesRequestTargetParser;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;

@Component
public class PlannerRuleEngine {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("(\\w+[-_]?\\w*)-?(service|gateway|api|worker|job)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final Pattern READ_ONLY_CONSTRAINT_PATTERN =
            Pattern.compile("(?iu)(?:禁止|不要|不得|不允许|严禁|无需|无须|只读|read[- ]?only|do not|don't|must not)"
                    + ".{0,32}(?:执行|变更|修改|操作|脚本|重启|扩容|缩容|删除|"
                    + "execute|change|mutat|script|restart|scale|patch|delete)");
    private final PlannerParameterResolver parameterResolver;
    private final PlannerTaskFactory taskFactory;

    public PlannerRuleEngine(PlannerParameterResolver parameterResolver, PlannerTaskFactory taskFactory) {
        this.parameterResolver = parameterResolver;
        this.taskFactory = taskFactory;
    }

    public List<String> splitTaskRequests(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return List.of("");
        }
        String[] parts = normalized.split("\\s*(?:然后|并且|同时|;|；|, then | and then )\\s*");
        List<String> requests = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                requests.add(part.trim());
            }
        }
        return requests.isEmpty() ? List.of(normalized) : requests;
    }

    public Task buildTask(
            String intent, TaskType taskType, String target, Map<String, Object> parameters, RiskLevel riskLevel) {
        return buildTask(intent, taskType, target, parameters, riskLevel, null);
    }

    public Task buildTask(
            String intent,
            TaskType taskType,
            String target,
            Map<String, Object> parameters,
            RiskLevel riskLevel,
            SopReference sopReference) {
        return taskFactory.create(intent, taskType, target, parameters, riskLevel, sopReference);
    }

    /**
     * Resolves only a versioned SOP returned by a successful knowledge lookup.
     *
     * <p>Missing identifiers are left missing rather than replaced with a generated placeholder.
     */
    public SopReference resolveSopReference(Map<String, Object> plannerKnowledge) {
        Map<String, Object> envelope = readMap(plannerKnowledge == null ? null : plannerKnowledge.get("sop"));
        if (!evidenceAvailable(envelope) || Boolean.TRUE.equals(envelope.get("simulation"))) {
            return null;
        }
        Map<String, Object> candidate = firstResult(envelope);
        String sopId = firstText(candidate, "sopId", "runbookId", "documentId", "id");
        String version = firstText(candidate, "version", "runbookVersion", "datasetVersion", "dataset_version");
        String source = firstText(candidate, "source");
        if (source.isBlank()) {
            source = firstText(envelope, "source", "tool");
        }
        if (sopId.isBlank() || version.isBlank() || source.isBlank()) {
            return null;
        }
        String title = firstText(candidate, "title", "name");
        return new SopReference(sopId, title, version, source);
    }

    public String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public Map<String, Object> mergeParameters(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (key != null && value != null && !String.valueOf(value).isBlank()) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    public Map<String, String> markLlmParameterSources(
            Map<String, String> baseSources, Map<String, Object> llmParameters) {
        Map<String, String> sources = new LinkedHashMap<>(baseSources);
        if (llmParameters != null) {
            llmParameters.keySet().forEach(key -> sources.put(String.valueOf(key), "llm_planner"));
        }
        return sources;
    }

    public List<String> mergeMissingSignals(List<String> ruleSignals, List<String> llmSignals) {
        List<String> merged = new ArrayList<>();
        if (ruleSignals != null) {
            merged.addAll(ruleSignals);
        }
        if (llmSignals != null) {
            for (String signal : llmSignals) {
                if (signal != null && !signal.isBlank() && !merged.contains(signal)) {
                    merged.add(signal);
                }
            }
        }
        return merged;
    }

    public String inferIntent(String normalized) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (isExplicitReadOnlyRequest(normalized)
                && !(lower.contains("日志")
                        || lower.contains("log")
                        || lower.contains("指标")
                        || lower.contains("监控")
                        || lower.contains("metric")
                        || lower.contains("cpu")
                        || lower.contains("memory"))) {
            return "GENERAL_DIAGNOSTICS";
        }
        if (lower.contains("查") || lower.contains("看") || lower.contains("日志") || lower.contains("log")) {
            return "QUERY_LOGS";
        }
        if (lower.contains("指标")
                || lower.contains("监控")
                || lower.contains("metric")
                || lower.contains("cpu")
                || lower.contains("memory")) {
            return "QUERY_METRICS";
        }
        if (lower.contains("重启") || lower.contains("restart")) {
            return "RESTART_SERVICE";
        }
        if (lower.contains("扩容") || lower.contains("缩容") || lower.contains("scale") || lower.contains("副本")) {
            return "SCALE_WORKLOAD";
        }
        if (lower.contains("配置")
                || lower.contains("超时")
                || lower.contains("timeout")
                || lower.contains("config")
                || lower.contains("调整")) {
            return "PATCH_CONFIG";
        }
        if (lower.contains("脚本") || lower.contains("script") || lower.contains("执行")) {
            return "EXECUTE_SCRIPT";
        }
        if (lower.contains("清理") || lower.contains("删除") || lower.contains("clean") || lower.contains("delete")) {
            return "CLEAN_DATA";
        }
        return "GENERAL_DIAGNOSTICS";
    }

    public String inferConfidence(String normalized, String intent) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        int matchCount = 0;
        if (intent.equals("RESTART_SERVICE") && (lower.contains("重启") || lower.contains("restart"))) {
            matchCount++;
        }
        if (intent.equals("QUERY_LOGS") && (lower.contains("日志") || lower.contains("log"))) {
            matchCount++;
        }
        if (intent.equals("SCALE_WORKLOAD") && (lower.contains("扩容") || lower.contains("scale"))) {
            matchCount++;
        }
        if (intent.equals("PATCH_CONFIG")
                && (lower.contains("配置") || lower.contains("config") || lower.contains("超时"))) {
            matchCount++;
        }
        if ("GENERAL_DIAGNOSTICS".equals(intent)) {
            return "LOW";
        }
        return matchCount > 0 ? "HIGH" : "MEDIUM";
    }

    public String inferTarget(String normalized) {
        KubernetesRequestTargetParser.Target kubernetesTarget = KubernetesRequestTargetParser.parse(normalized);
        if (kubernetesTarget.hasResource()) {
            return kubernetesTarget.resourceName();
        }
        Matcher matcher = SERVICE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(0);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("payment")) {
            return "payment-service";
        }
        if (lower.contains("gateway")) {
            return "gateway-service";
        }
        if (lower.contains("order")) {
            return "order-service";
        }
        if (lower.contains("user")) {
            return "user-service";
        }
        return "current-scope";
    }

    public String inferTargetSource(String normalized, String target) {
        if (KubernetesRequestTargetParser.parse(normalized).hasResource()) {
            return "extracted_from_request";
        }
        if (SERVICE_PATTERN.matcher(normalized).find()) {
            return "extracted_from_request";
        }
        if (!target.equals("current-scope")) {
            return "inferred_from_keywords";
        }
        return "scope_fallback";
    }

    public TaskType mapIntentToTaskType(String intent) {
        return switch (intent) {
            case "QUERY_LOGS" -> TaskType.QUERY_LOGS;
            case "QUERY_METRICS" -> TaskType.QUERY_METRICS;
            case "RESTART_SERVICE" -> TaskType.RESTART_SERVICE;
            case "SCALE_WORKLOAD" -> TaskType.SCALE_WORKLOAD;
            case "PATCH_CONFIG" -> TaskType.PATCH_CONFIG;
            case "EXECUTE_SCRIPT" -> TaskType.EXECUTE_SCRIPT;
            case "CLEAN_DATA" -> TaskType.CLEAN_DATA;
            case "GENERAL_DIAGNOSTICS" -> TaskType.QUERY_METRICS;
            default -> TaskType.QUERY_METRICS;
        };
    }

    public Map<String, Object> inferParameters(
            String normalized, TaskType taskType, String target, Map<String, Object> plannerKnowledge) {
        return parameterResolver.inferParameters(normalized, taskType, target, plannerKnowledge);
    }

    public Map<String, String> buildParameterSources(
            Map<String, Object> parameters, String normalized, Map<String, Object> plannerKnowledge) {
        return parameterResolver.parameterSources(parameters, normalized, plannerKnowledge);
    }

    public RiskLevel inferRiskLevel(
            String intent,
            TaskType taskType,
            String target,
            Map<String, Object> parameters,
            Map<String, Object> plannerKnowledge) {
        String criticality =
                String.valueOf(readServiceMetadata(plannerKnowledge).getOrDefault("criticality", "medium"));
        String environment =
                String.valueOf(readServiceMetadata(plannerKnowledge).getOrDefault("environment", "lab"));
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

    public List<String> identifyMissingSignals(
            String normalized,
            TaskType taskType,
            Map<String, Object> parameters,
            Map<String, Object> plannerKnowledge) {
        return identifyMissingSignals(normalized, taskType, null, parameters, plannerKnowledge);
    }

    public List<String> identifyMissingSignals(
            String normalized,
            TaskType taskType,
            String target,
            Map<String, Object> parameters,
            Map<String, Object> plannerKnowledge) {
        List<String> missing = new ArrayList<>();
        if (taskType == TaskType.PATCH_CONFIG && "config_key".equals(parameters.get("configKey"))) {
            missing.add("specific_config_key_not_identified");
        }
        if (taskType == TaskType.SCALE_WORKLOAD
                && !NUMBER_PATTERN.matcher(normalized).find()
                && !readSupplementalSignals(plannerKnowledge).containsKey("recommendedReplicas")) {
            missing.add("target_replica_count_not_specified");
        }
        if (isMutation(taskType) && isScopeTarget(target)) {
            missing.add("specific_target_not_identified");
        }
        if (!evidenceAvailable(plannerKnowledge.get("sop"))) {
            missing.add("sop_context_unavailable");
        }
        if (!evidenceAvailable(plannerKnowledge.get("serviceMetadata"))) {
            missing.add("service_metadata_unavailable");
        }
        return missing;
    }

    public boolean shouldRetryForMissingSignals(TaskType taskType, List<String> missingSignals, int currentLoop) {
        if (currentLoop > 0 || missingSignals.isEmpty()) {
            return false;
        }
        return (taskType == TaskType.SCALE_WORKLOAD && missingSignals.contains("target_replica_count_not_specified"))
                || (taskType == TaskType.PATCH_CONFIG && missingSignals.contains("specific_config_key_not_identified"))
                || (isMutation(taskType) && missingSignals.contains("specific_target_not_identified"));
    }

    public boolean requiresClarification(TaskType taskType, List<String> missingSignals, int currentLoop) {
        if (currentLoop <= 0 || !isMutation(taskType)) {
            return false;
        }
        return missingSignals.contains("specific_target_not_identified")
                || (taskType == TaskType.SCALE_WORKLOAD
                        && missingSignals.contains("target_replica_count_not_specified"))
                || (taskType == TaskType.PATCH_CONFIG && missingSignals.contains("specific_config_key_not_identified"));
    }

    public String buildPlanSummary(
            String intent, TaskType taskType, String target, RiskLevel riskLevel, List<String> consultedTools) {
        return String.format(
                "Intent=%s, TaskType=%s, Target=%s, Risk=%s, ConsultedTools=%s",
                intent, taskType, target, riskLevel, consultedTools);
    }

    private Map<String, Object> readServiceMetadata(Map<String, Object> plannerKnowledge) {
        return readMap(plannerKnowledge.get("serviceMetadata"));
    }

    private Map<String, Object> readSupplementalSignals(Map<String, Object> plannerKnowledge) {
        return readMap(plannerKnowledge.get("supplementalSignals"));
    }

    private Map<String, Object> firstResult(Map<String, Object> envelope) {
        for (String key : List.of("items", "results", "documents", "hits")) {
            Object value = envelope.get(key);
            if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
                return readMap(map);
            }
        }
        return envelope;
    }

    private Map<String, Object> readMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
        return result;
    }

    private boolean evidenceAvailable(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return false;
        }
        Object status = map.get("collectionStatus");
        return status == null || "SUCCEEDED".equalsIgnoreCase(String.valueOf(status));
    }

    private String firstText(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    public boolean isMutation(TaskType taskType) {
        return taskType != null && !taskType.name().startsWith("QUERY");
    }

    public boolean isExplicitReadOnlyRequest(String request) {
        return request != null && READ_ONLY_CONSTRAINT_PATTERN.matcher(request).find();
    }

    private boolean isScopeTarget(Object target) {
        if (target == null) {
            return true;
        }
        String value = String.valueOf(target);
        return value.isBlank() || "current-scope".equals(value) || "unknown-service".equals(value);
    }
}
