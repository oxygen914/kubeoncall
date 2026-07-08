package com.kubeoncall.memory;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertMemoryService {

    private final MemoryService memoryService;
    private final KubeOnCallProperties properties;

    public AlertMemoryService(MemoryService memoryService,
                              KubeOnCallProperties properties) {
        this.memoryService = memoryService;
        this.properties = properties;
    }

    public List<MemoryEntry> recall(NormalizedAlarmEvent event) {
        if (event == null || !properties.getMemory().isEnabled()) {
            return List.of();
        }
        LinkedHashMap<String, MemoryEntry> recalled = new LinkedHashMap<>();
        String query = String.join(" ",
                safe(event.alertName()),
                safe(event.service()),
                safe(event.resourceName()),
                safe(event.fingerprint()));
        addAll(recalled, event.fingerprint(), query, "fingerprint");
        addAll(recalled, event.service(), query, "service");
        addAll(recalled, event.resourceName(), query, "resource");
        return recalled.values().stream()
                .limit(Math.max(1, properties.getMemory().getInjectMaxEntries()))
                .toList();
    }

    public MemoryEntry rememberResolution(NormalizedAlarmEvent event, String summary) {
        if (event == null) {
            throw new IllegalArgumentException("alarm event must not be null");
        }
        MemoryEntry entry = new MemoryEntry(
                null,
                MemoryType.INCIDENT_SUMMARY,
                event.fingerprint() == null || event.fingerprint().isBlank() ? MemoryScope.SERVICE : MemoryScope.FINGERPRINT,
                safe(event.alertName()),
                summary,
                event.service(),
                event.resourceName(),
                event.fingerprint(),
                Instant.now(),
                Instant.now(),
                Map.of("source", "alarm", "alert_name", safe(event.alertName()))
        );
        return memoryService.remember(entry);
    }

    private void addAll(LinkedHashMap<String, MemoryEntry> recalled, String value, String query, String key) {
        if (value == null || value.isBlank()) {
            return;
        }
        Map<String, String> filters = Map.of(key, value);
        for (MemoryEntry entry : memoryService.search(query, filters, Math.max(1, properties.getMemory().getInjectMaxEntries()))) {
            recalled.putIfAbsent(entry.id(), entry);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
