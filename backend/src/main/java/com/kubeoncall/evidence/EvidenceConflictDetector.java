package com.kubeoncall.evidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Finds unresolved high-level contradictions without asking the model to hide or reconcile them. */
@Component
public class EvidenceConflictDetector {

    private static final Set<String> HEALTHY = Set.of("ready", "healthy", "available", "succeeded", "resolved");
    private static final Set<String> UNHEALTHY =
            Set.of("notready", "unhealthy", "unavailable", "failed", "pending", "crashloopbackoff", "oomkilled");

    public List<Map<String, Object>> detect(List<EvidenceItem> evidence) {
        Map<String, Set<String>> statesByResource = new LinkedHashMap<>();
        for (EvidenceItem item : evidence == null ? List.<EvidenceItem>of() : evidence) {
            if (!item.succeeded() || item.type() != EvidenceType.RESOURCE_STATE) {
                continue;
            }
            String resourceKey = item.resource().uid().isBlank()
                    ? item.resource().kind() + "/" + item.resource().name()
                    : item.resource().uid();
            Set<String> states = statesByResource.computeIfAbsent(resourceKey, ignored -> new HashSet<>());
            states.addAll(classify(item.summary() + " " + item.snippet()));
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        statesByResource.forEach((resource, states) -> {
            if (states.contains("HEALTHY") && states.contains("UNHEALTHY")) {
                conflicts.add(Map.of(
                        "type",
                        "RESOURCE_HEALTH_CONFLICT",
                        "resource",
                        resource,
                        "states",
                        List.copyOf(states),
                        "resolved",
                        false));
            }
        });
        return List.copyOf(conflicts);
    }

    private Set<String> classify(String value) {
        String normalized = value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        Set<String> tokens = normalized.isBlank()
                ? Set.of()
                : java.util.Arrays.stream(normalized.split("\\s+")).collect(java.util.stream.Collectors.toSet());
        Set<String> result = new HashSet<>();
        if (HEALTHY.stream().anyMatch(tokens::contains)) {
            result.add("HEALTHY");
        }
        if (UNHEALTHY.stream().anyMatch(tokens::contains)) {
            result.add("UNHEALTHY");
        }
        return result;
    }
}
