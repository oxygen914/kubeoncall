package com.kubeoncall.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillExecutionPolicy;
import com.kubeoncall.tool.ToolExecutor;

/** Collects typed Kubernetes Events and current/previous Pod logs through the governed executor. */
@Component
public class KubernetesEvidenceCollector {

    private final ToolExecutor kubernetes;
    private final KubeOnCallProperties properties;
    private final EvidenceScopePolicy scopePolicy;
    private final EvidenceItemFactory factory;
    private final KubeOnCallMetricsService metrics;

    public KubernetesEvidenceCollector(
            List<ToolExecutor> executors,
            KubeOnCallProperties properties,
            EvidenceScopePolicy scopePolicy,
            EvidenceItemFactory factory,
            KubeOnCallMetricsService metrics) {
        this.kubernetes = executors.stream()
                .filter(executor -> "kubernetes".equals(executor.getExecutorKind()))
                .findFirst()
                .orElse(null);
        this.properties = properties;
        this.scopePolicy = scopePolicy;
        this.factory = factory;
        this.metrics = metrics;
    }

    public List<EvidenceItem> collect(EvidenceCollectionScope scope) {
        return collect(scope, SkillExecutionPolicy.ToolAccess.unrestricted());
    }

    public List<EvidenceItem> collect(EvidenceCollectionScope scope, SkillExecutionPolicy.ToolAccess toolAccess) {
        SkillExecutionPolicy.ToolAccess access =
                toolAccess == null ? SkillExecutionPolicy.ToolAccess.unrestricted() : toolAccess;
        List<EvidenceItem> items = new ArrayList<>();
        boolean resourceAllowed = access.allows("kubernetes.describeResource");
        boolean eventsAllowed = access.allows("kubernetes.queryEvents");
        boolean logsAllowed = access.allowsAny("kubernetes.queryPodLogs", "kubernetes.queryLogs");
        addSkillRejections(items, scope, resourceAllowed, eventsAllowed, logsAllowed);
        var rejection = scopePolicy.rejection(scope);
        if (rejection.isPresent()) {
            if (properties.getAiOperations().isEvidenceK8sResourceStateEnabled() && resourceAllowed) {
                items.add(statusItem(
                        scope, EvidenceType.RESOURCE_STATE, EvidenceCollectionStatus.FORBIDDEN, rejection.get()));
            }
            if (properties.getAiOperations().isEvidenceK8sEventsEnabled() && eventsAllowed) {
                items.add(
                        statusItem(scope, EvidenceType.K8S_EVENT, EvidenceCollectionStatus.FORBIDDEN, rejection.get()));
            }
            if (properties.getAiOperations().isEvidencePodLogsEnabled() && logsAllowed) {
                items.add(statusItem(scope, EvidenceType.POD_LOG, EvidenceCollectionStatus.FORBIDDEN, rejection.get()));
            }
            return List.copyOf(items);
        }
        if (kubernetes == null) {
            if (properties.getAiOperations().isEvidenceK8sResourceStateEnabled() && resourceAllowed) {
                items.add(factory.unavailable(scope, EvidenceType.RESOURCE_STATE, "kubernetes-api", "CLIENT_MISSING"));
            }
            if (properties.getAiOperations().isEvidenceK8sEventsEnabled() && eventsAllowed) {
                items.add(factory.unavailable(scope, EvidenceType.K8S_EVENT, "kubernetes-api", "CLIENT_MISSING"));
            }
            if (properties.getAiOperations().isEvidencePodLogsEnabled() && logsAllowed) {
                items.add(factory.unavailable(scope, EvidenceType.POD_LOG, "kubernetes-api", "CLIENT_MISSING"));
            }
            return List.copyOf(items);
        }
        if (properties.getAiOperations().isEvidenceK8sResourceStateEnabled() && resourceAllowed) {
            items.addAll(call(scope, EvidenceType.RESOURCE_STATE, "describeResource", baseParameters(scope), false));
        }
        if (properties.getAiOperations().isEvidenceK8sEventsEnabled() && eventsAllowed) {
            items.addAll(call(scope, EvidenceType.K8S_EVENT, "queryEvents", baseParameters(scope), false));
        }
        if (properties.getAiOperations().isEvidencePodLogsEnabled() && logsAllowed) {
            items.addAll(call(scope, EvidenceType.POD_LOG, "queryPodLogs", logParameters(scope, false), false));
            items.addAll(call(scope, EvidenceType.POD_LOG, "queryPodLogs", logParameters(scope, true), true));
        }
        return List.copyOf(items);
    }

    private void addSkillRejections(
            List<EvidenceItem> items,
            EvidenceCollectionScope scope,
            boolean resourceAllowed,
            boolean eventsAllowed,
            boolean logsAllowed) {
        if (properties.getAiOperations().isEvidenceK8sResourceStateEnabled() && !resourceAllowed) {
            items.add(statusItem(
                    scope, EvidenceType.RESOURCE_STATE, EvidenceCollectionStatus.FORBIDDEN, "SKILL_TOOL_NOT_ALLOWED"));
        }
        if (properties.getAiOperations().isEvidenceK8sEventsEnabled() && !eventsAllowed) {
            items.add(statusItem(
                    scope, EvidenceType.K8S_EVENT, EvidenceCollectionStatus.FORBIDDEN, "SKILL_TOOL_NOT_ALLOWED"));
        }
        if (properties.getAiOperations().isEvidencePodLogsEnabled() && !logsAllowed) {
            items.add(statusItem(
                    scope, EvidenceType.POD_LOG, EvidenceCollectionStatus.FORBIDDEN, "SKILL_TOOL_NOT_ALLOWED"));
        }
    }

    private List<EvidenceItem> call(
            EvidenceCollectionScope scope,
            EvidenceType type,
            String action,
            Map<String, Object> parameters,
            boolean previous) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> response = kubernetes.execute(action, parameters);
        long latencyMs = Math.max(0, System.currentTimeMillis() - startedAt);
        EvidenceCollectionStatus status = "success".equalsIgnoreCase(String.valueOf(response.get("status")))
                ? EvidenceCollectionStatus.SUCCEEDED
                : mapFailure(response);
        PreviousLogClassification previousLogClassification = previous && status == EvidenceCollectionStatus.SUCCEEDED
                ? classifyPreviousLog(response.get("response"))
                : null;
        if (previousLogClassification != null) {
            status = previousLogClassification.status();
        }
        metrics.recordEvidenceCollection("kubernetes-api:" + action, status.name(), latencyMs);
        if (status != EvidenceCollectionStatus.SUCCEEDED) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("source", "kubernetes-api");
            failed.put("collectionStatus", status.name());
            failed.put(
                    "errorType",
                    previousLogClassification == null
                            ? response.getOrDefault("errorType", "KUBERNETES_UNAVAILABLE")
                            : previousLogClassification.errorType());
            failed.put("latencyMs", latencyMs);
            failed.put("previous", previous);
            return List.of(factory.fromMap(scope, type, failed, "kubernetes-api"));
        }
        List<Map<String, Object>> payloads = flatten(response.get("response"), type);
        if (payloads.isEmpty()) {
            return List.of(statusItem(
                    scope, type, EvidenceCollectionStatus.EMPTY, previous ? "PREVIOUS_LOG_EMPTY" : "", previous));
        }
        return payloads.stream()
                .map(payload -> {
                    Map<String, Object> normalized = new LinkedHashMap<>(payload);
                    normalized.put("source", "kubernetes-api");
                    normalized.put("collectionStatus", "SUCCEEDED");
                    normalized.put("latencyMs", latencyMs);
                    normalized.put("previous", previous);
                    return factory.fromMap(scope, type, normalized, "kubernetes-api");
                })
                .toList();
    }

    private EvidenceItem statusItem(
            EvidenceCollectionScope scope, EvidenceType type, EvidenceCollectionStatus status, String errorType) {
        return statusItem(scope, type, status, errorType, false);
    }

    private EvidenceItem statusItem(
            EvidenceCollectionScope scope,
            EvidenceType type,
            EvidenceCollectionStatus status,
            String errorType,
            boolean previous) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", "kubernetes-api");
        value.put("collectionStatus", status.name());
        if (type == EvidenceType.POD_LOG) {
            value.put("previous", previous);
        }
        if (errorType != null && !errorType.isBlank()) {
            value.put("errorType", errorType);
        }
        return factory.fromMap(scope, type, value, "kubernetes-api");
    }

    private Map<String, Object> baseParameters(EvidenceCollectionScope scope) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("cluster", scope.cluster());
        values.put("namespace", scope.namespace());
        put(values, "resourceKind", scope.resource().kind());
        put(values, "resourceName", scope.resource().name());
        put(values, "resourceUid", scope.resource().uid());
        values.put("startTime", scope.start().toString());
        values.put("endTime", scope.end().toString());
        return values;
    }

    private Map<String, Object> logParameters(EvidenceCollectionScope scope, boolean previous) {
        Map<String, Object> values = new LinkedHashMap<>(baseParameters(scope));
        values.put("previous", previous);
        values.put("tailLines", Math.min(500, properties.getAiOperations().getLokiMaxLines()));
        return values;
    }

    private static List<Map<String, Object>> flatten(Object response, EvidenceType type) {
        if (response instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(KubernetesEvidenceCollector::stringMap)
                    .toList();
        }
        Map<String, Object> root = map(response);
        for (String key : type == EvidenceType.K8S_EVENT
                ? List.of("items", "events", "results")
                : List.of("items", "logs", "results", "entries")) {
            Object candidate = root.get(key);
            if (candidate instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(KubernetesEvidenceCollector::stringMap)
                        .toList();
            }
        }
        return root.isEmpty() ? List.of() : List.of(root);
    }

    private static EvidenceCollectionStatus mapFailure(Map<String, Object> response) {
        String error = String.valueOf(response.getOrDefault("errorType", "")).toUpperCase();
        Object httpStatus = response.get("httpStatus");
        if (error.contains("FORBIDDEN")
                || error.contains("RBAC")
                || error.contains("403")
                || httpStatus instanceof Number number && number.intValue() == 403) {
            return EvidenceCollectionStatus.FORBIDDEN;
        }
        return EvidenceCollectionStatus.UNAVAILABLE;
    }

    private static PreviousLogClassification classifyPreviousLog(Object response) {
        String normalized = String.valueOf(response).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        boolean previousContainerAbsent = normalized.contains("previous terminated container")
                && (normalized.contains("not found")
                        || normalized.contains("not available")
                        || normalized.contains("no previous"));
        if (previousContainerAbsent
                || normalized.contains("no previous logs")
                || normalized.contains("previous log is not available")) {
            return new PreviousLogClassification(EvidenceCollectionStatus.EMPTY, "PREVIOUS_LOG_EMPTY");
        }
        if (normalized.contains("unable to retrieve container logs")
                || normalized.contains("kubelet")
                || normalized.contains("dial tcp")
                || normalized.contains("connection refused")
                || normalized.contains("service unavailable")
                || normalized.contains("i/o timeout")
                || normalized.contains("tls handshake timeout")
                || normalized.contains("context deadline exceeded")
                || normalized.contains("kubernetes_unavailable")
                || normalized.contains("kubernetes_timeout")) {
            return new PreviousLogClassification(EvidenceCollectionStatus.UNAVAILABLE, "PREVIOUS_LOG_UNAVAILABLE");
        }
        return null;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? stringMap(raw) : Map.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static void put(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private record PreviousLogClassification(EvidenceCollectionStatus status, String errorType) {}
}
