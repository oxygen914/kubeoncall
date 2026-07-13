package com.kubeoncall.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

@Service
public class DefaultMemoryInjector implements MemoryInjector {

    private final MemoryService memoryService;
    private final KubeOnCallProperties properties;

    public DefaultMemoryInjector(MemoryService memoryService, KubeOnCallProperties properties) {
        this.memoryService = memoryService;
        this.properties = properties;
    }

    @Override
    public MemoryInjection inject(String query, Map<String, String> filters) {
        if (!properties.getMemory().isEnabled()) {
            return MemoryInjection.empty();
        }
        try {
            List<MemoryEntry> entries =
                    memoryService
                            .search(
                                    query,
                                    filters,
                                    Math.max(1, properties.getMemory().getInjectMaxEntries()))
                            .stream()
                            .filter(this::isInjectable)
                            .limit(Math.max(1, properties.getMemory().getInjectMaxEntries()))
                            .toList();
            if (entries.isEmpty()) {
                return MemoryInjection.empty();
            }
            return new MemoryInjection(entries, buildPrompt(entries), "");
        } catch (RuntimeException ex) {
            return new MemoryInjection(List.of(), "", "memory injection unavailable: " + ex.getMessage());
        }
    }

    private boolean isInjectable(MemoryEntry entry) {
        return entry.type() == MemoryType.SERVICE_FACT || entry.type() == MemoryType.KNOWN_PITFALL;
    }

    private String buildPrompt(List<MemoryEntry> entries) {
        StringBuilder builder = new StringBuilder();
        builder.append("Long-term memory hints. Verify current cluster state before using them.");
        for (MemoryEntry entry : entries) {
            builder.append("\n- [")
                    .append(entry.type().name())
                    .append(", ")
                    .append(freshness(entry.updatedAt()))
                    .append("] ")
                    .append(nonBlank(entry.subject(), "memory"))
                    .append(": ")
                    .append(abbreviate(entry.content(), 360));
        }
        return builder.toString();
    }

    private String freshness(Instant updatedAt) {
        if (updatedAt == null) {
            return "unknown freshness";
        }
        long ageDays = Math.max(0, Duration.between(updatedAt, Instant.now()).toDays());
        if (ageDays > properties.getMemory().getStaleAfterDays()) {
            return "stale " + ageDays + "d old";
        }
        return "fresh " + ageDays + "d old";
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
