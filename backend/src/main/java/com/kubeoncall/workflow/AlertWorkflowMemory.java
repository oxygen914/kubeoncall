package com.kubeoncall.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.memory.AlertMemoryService;
import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryExtractor;

@Service
public class AlertWorkflowMemory {

    private static final Logger log = LoggerFactory.getLogger(AlertWorkflowMemory.class);

    private final AlertMemoryService alertMemoryService;
    private final MemoryExtractor memoryExtractor;

    public AlertWorkflowMemory(AlertMemoryService alertMemoryService, MemoryExtractor memoryExtractor) {
        this.alertMemoryService = alertMemoryService;
        this.memoryExtractor = memoryExtractor;
    }

    public Recall recall(NormalizedAlarmEvent event) {
        try {
            List<MemoryEntry> entries = alertMemoryService.recall(event);
            return new Recall(entries, "");
        } catch (RuntimeException ex) {
            log.warn(
                    "Alert memory recall failed; continuing without recalled memory: errorType={}",
                    ex.getClass().getSimpleName());
            return new Recall(
                    List.of(), "alert memory recall failed: " + ex.getClass().getSimpleName());
        }
    }

    public void attach(AlertWorkflowContext context, Recall recall) {
        context.putAttribute("alertMemories", payload(recall.entries()));
        context.putAttribute("alertMemoryEntries", recall.entries());
        if (!recall.warning().isBlank()) {
            context.putAttribute("alertMemoryWarning", recall.warning());
        }
    }

    public void extract(NormalizedAlarmEvent event, String summary, AlertWorkflowContext context) {
        try {
            memoryExtractor.extractFromAlarm(event, summary);
        } catch (RuntimeException ex) {
            log.warn(
                    "Alert memory extraction failed; continuing workflow completion: errorType={}",
                    ex.getClass().getSimpleName());
            context.putAttribute(
                    "alertMemoryExtractionWarning",
                    "alert memory extraction failed: " + ex.getClass().getSimpleName());
        }
    }

    public long consumedCount(AlertWorkflowContext context) {
        Object value = context.getAttribute("alertMemoryConsumed");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private List<Map<String, Object>> payload(List<MemoryEntry> entries) {
        return entries.stream()
                .map(entry -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", entry.id());
                    payload.put(
                            "type", entry.type() == null ? null : entry.type().name());
                    payload.put(
                            "scope",
                            entry.scope() == null ? null : entry.scope().name());
                    payload.put("subject", entry.subject());
                    payload.put("service", entry.service());
                    payload.put("resource", entry.resource());
                    payload.put("fingerprint", entry.fingerprint());
                    payload.put(
                            "updatedAt",
                            entry.updatedAt() == null ? null : entry.updatedAt().toString());
                    payload.put("content", entry.content());
                    return payload;
                })
                .toList();
    }

    public record Recall(List<MemoryEntry> entries, String warning) {

        public Recall {
            entries = entries == null ? List.of() : List.copyOf(entries);
            warning = warning == null ? "" : warning;
        }
    }
}
