package com.kubeoncall.memory;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class HeuristicMemoryExtractor implements MemoryExtractor {

    private final MemoryService memoryService;
    private final KubeOnCallProperties properties;

    public HeuristicMemoryExtractor(MemoryService memoryService,
                                    KubeOnCallProperties properties) {
        this.memoryService = memoryService;
        this.properties = properties;
    }

    @Override
    public void extractFromAsk(GraphState state, String answer) {
        if (!properties.getMemory().isEnabled() || shouldSkip(answer)) {
            return;
        }
        MemoryType type = inferType(answer);
        if (type == null) {
            return;
        }
        String service = state.getCurrentTask() == null ? null : state.getCurrentTask().target();
        MemoryScope scope = service == null || service.isBlank() ? MemoryScope.GLOBAL : MemoryScope.SERVICE;
        memoryService.remember(new MemoryEntry(
                null,
                type,
                scope,
                buildSubject(type, service),
                abbreviate(answer, 1000),
                service,
                null,
                null,
                Instant.now(),
                Instant.now(),
                Map.of("source", "ask", "execution_id", safe(state.getExecutionId()))
        ));
    }

    @Override
    public void extractFromAlarm(NormalizedAlarmEvent event, String summary) {
        if (!properties.getMemory().isEnabled() || event == null || shouldSkip(summary)) {
            return;
        }
        memoryService.remember(new MemoryEntry(
                null,
                MemoryType.INCIDENT_SUMMARY,
                event.fingerprint() == null || event.fingerprint().isBlank() ? MemoryScope.SERVICE : MemoryScope.FINGERPRINT,
                safe(event.alertName()),
                abbreviate(summary, 1000),
                event.service(),
                event.resourceName(),
                event.fingerprint(),
                Instant.now(),
                Instant.now(),
                Map.of("source", "alarm", "alert_name", safe(event.alertName()))
        ));
    }

    private MemoryType inferType(String answer) {
        String lower = answer == null ? "" : answer.toLowerCase();
        if (lower.contains("known pitfall") || lower.contains("pitfall") || lower.contains("注意") || lower.contains("坑")) {
            return MemoryType.KNOWN_PITFALL;
        }
        if (lower.contains("owned by") || lower.contains("owner") || lower.contains("负责人") || lower.contains("归属")) {
            return MemoryType.SERVICE_FACT;
        }
        return null;
    }

    private boolean shouldSkip(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String lower = content.toLowerCase();
        if (lower.contains("kubectl ")) {
            return true;
        }
        if (lower.contains("current value") || lower.contains("当前值") || lower.contains("实时")) {
            return true;
        }
        return content.length() > 3000 && (lower.contains("sop-") || lower.contains("standard procedure"));
    }

    private String buildSubject(MemoryType type, String service) {
        String prefix = service == null || service.isBlank() ? "global" : service;
        return prefix + " " + type.name().toLowerCase();
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
