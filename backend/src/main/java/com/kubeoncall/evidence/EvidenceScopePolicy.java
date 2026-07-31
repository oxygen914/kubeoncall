package com.kubeoncall.evidence;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

/** Fail-closed cluster/namespace allowlist for evidence adapters. */
@Component
public class EvidenceScopePolicy {

    private final List<String> allowedClusters;
    private final List<String> allowedNamespaces;

    public EvidenceScopePolicy(KubeOnCallProperties properties) {
        allowedClusters = normalize(properties.getAiOperations().getAllowedClusters());
        allowedNamespaces = normalize(properties.getAiOperations().getAllowedNamespaces());
    }

    public Optional<String> rejection(EvidenceCollectionScope scope) {
        if (scope.cluster().isBlank() || !allowedClusters.contains(normalize(scope.cluster()))) {
            return Optional.of("CLUSTER_NOT_ALLOWED");
        }
        if (scope.namespace().isBlank() || !allowedNamespaces.contains(normalize(scope.namespace()))) {
            return Optional.of("NAMESPACE_NOT_ALLOWED");
        }
        return Optional.empty();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(EvidenceScopePolicy::normalize)
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
