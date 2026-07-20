package com.kubeoncall.agent.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.task.TaskType;

@Component
public class PlannerParameterResolver {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    public Map<String, Object> inferParameters(
            String normalized, TaskType taskType, String target, Map<String, Object> plannerKnowledge) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Map<String, Object> supplemental = map(plannerKnowledge.get("supplementalSignals"));
        parameters.put("namespace", map(plannerKnowledge.get("serviceMetadata")).getOrDefault("namespace", "default"));
        switch (taskType) {
            case QUERY_LOGS -> {
                parameters.put("keyword", keyword(normalized));
                parameters.put("lookbackMinutes", 15);
            }
            case QUERY_METRICS -> {
                parameters.put("metricNames", List.of("cpu_usage", "memory_usage"));
                parameters.put("windowMinutes", metricWindow(plannerKnowledge));
            }
            case PATCH_CONFIG -> {
                parameters.put("configKey", configKey(normalized, supplemental));
                parameters.put("desiredValue", configValue(normalized));
                parameters.put("changeReason", "operator_request");
            }
            case RESTART_SERVICE -> {
                parameters.put("rolloutStrategy", "rolling");
                parameters.put("reason", "operator_initiated_restart");
            }
            case SCALE_WORKLOAD -> {
                parameters.put("replicas", replicas(normalized, supplemental));
                parameters.put("reason", "manual_scaling");
            }
            case EXECUTE_SCRIPT -> {
                parameters.put("scriptName", "diagnostic.sh");
                parameters.put("args", List.of());
                parameters.put("readonly", true);
            }
            case CLEAN_DATA -> parameters.put("target", target);
        }
        return parameters;
    }

    public Map<String, String> parameterSources(
            Map<String, Object> parameters, String normalized, Map<String, Object> knowledge) {
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, Object> metadata = map(knowledge.get("serviceMetadata"));
        Map<String, Object> supplemental = map(knowledge.get("supplementalSignals"));
        for (String key : parameters.keySet()) {
            if (key.equals("namespace") && metadata.containsKey("namespace"))
                sources.put(key, "tool:cmdb.getServiceMetadata");
            else if (key.equals("windowMinutes") && knowledge.containsKey("metricsContext"))
                sources.put(key, "tool:prometheus.queryRange");
            else if (key.equals("replicas")
                    && supplemental.containsKey("recommendedReplicas")
                    && !NUMBER_PATTERN.matcher(normalized).find()) sources.put(key, "tool:topology.getServiceTopology");
            else if (key.equals("configKey")
                    && supplemental.containsKey("recommendedConfigKey")
                    && !normalized.toLowerCase(Locale.ROOT).contains("timeout"))
                sources.put(key, "tool:knowledge.searchSop");
            else if (NUMBER_PATTERN.matcher(normalized).find() && (key.equals("replicas") || key.contains("Value")))
                sources.put(key, "extracted_from_request");
            else sources.put(key, "inferred_from_intent");
        }
        return sources;
    }

    private String keyword(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("超时") || lower.contains("timeout")) return "timeout";
        if (lower.contains("异常") || lower.contains("exception")) return "exception";
        return "error";
    }

    private String configKey(String value, Map<String, Object> supplemental) {
        if (value.contains("超时") || value.contains("timeout")) return "timeout";
        Object recommended = supplemental.get("recommendedConfigKey");
        return recommended == null || String.valueOf(recommended).isBlank()
                ? "config_key"
                : String.valueOf(recommended);
    }

    private String configValue(String value) {
        Matcher matcher = NUMBER_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() + "s" : "60s";
    }

    private int replicas(String value, Map<String, Object> supplemental) {
        Matcher matcher = NUMBER_PATTERN.matcher(value);
        if (matcher.find()) return Integer.parseInt(matcher.group());
        Object recommended = supplemental.get("recommendedReplicas");
        return recommended instanceof Number number ? number.intValue() : 3;
    }

    private int metricWindow(Map<String, Object> knowledge) {
        Object context = knowledge.get("metricsContext");
        return context instanceof Map<?, ?> raw && raw.get("windowMinutes") instanceof Number number
                ? number.intValue()
                : 10;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
