package com.kubeoncall.evidence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.common.k8s.KubernetesRequestTargetParser;
import com.kubeoncall.domain.graph.GraphState;

/** Resolves an evidence scope only from authenticated request context and known graph facts. */
@Component
public class EvidenceScopeResolver {

    private final int lookbackMinutes;

    public EvidenceScopeResolver(KubeOnCallProperties properties) {
        lookbackMinutes =
                Math.max(1, Math.min(1440, properties.getAiOperations().getEvidenceLookbackMinutes()));
    }

    public EvidenceCollectionScope resolve(GraphState state, String fallbackTarget) {
        Map<String, Object> scope = map(state.getContext().get("requestScope"));
        KubernetesRequestTargetParser.Target parsedTarget = KubernetesRequestTargetParser.parse(state.getUserRequest());
        String cluster = first(scope.get("cluster"), state.getContext().get("cluster"));
        String environment = first(scope.get("environment"), state.getContext().get("environment"));
        String namespace = first(scope.get("namespace"), state.getContext().get("namespace"), parsedTarget.namespace());
        String kind =
                first(scope.get("resourceKind"), state.getContext().get("resourceKind"), parsedTarget.resourceKind());
        String name = first(
                scope.get("resourceName"),
                state.getContext().get("resourceName"),
                parsedTarget.resourceName(),
                fallbackTarget);
        String uid = first(scope.get("resourceUid"), state.getContext().get("resourceUid"));
        Instant end = Instant.now();
        return new EvidenceCollectionScope(
                state.getExecutionId(),
                cluster,
                environment,
                namespace,
                new EvidenceResource(kind, name, uid),
                end.minus(lookbackMinutes, ChronoUnit.MINUTES),
                end);
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }
}
