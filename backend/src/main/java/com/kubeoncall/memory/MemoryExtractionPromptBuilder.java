package com.kubeoncall.memory;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class MemoryExtractionPromptBuilder {

    private final ObjectMapper objectMapper;

    public MemoryExtractionPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemPrompt() {
        return """
                Extract only durable operational knowledge from the supplied evidence.
                Return one JSON object: {"memories":[...]}. Each memory must contain
                memoryType, scope, subject, content, evidence, confidence. Optional fields are
                service, resource, fingerprint. memoryType must be SERVICE_FACT, KNOWN_PITFALL,
                DEVICE_HISTORY, INCIDENT_SUMMARY, USER_PREFERENCE, or USER_NOTE. scope must be
                GLOBAL, SERVICE, RESOURCE, or FINGERPRINT. Evidence entries must quote a short
                phrase from the supplied content. Do not store secrets, live metric values,
                kubectl commands, speculative claims, or instructions to mutate systems.
                Return an empty memories array when no durable fact is supported.
                """;
    }

    public String userPrompt(MemoryExtractionTask task) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", task.id());
            payload.put("source", task.metadata().getOrDefault("source", "unknown"));
            payload.put("suggestedType", task.memoryType().name());
            payload.put("suggestedScope", task.scope().name());
            payload.put("subject", task.subject());
            payload.put("content", task.content());
            payload.put("service", task.service());
            payload.put("resource", task.resource());
            payload.put("fingerprint", task.fingerprint());
            payload.put("provenance", task.metadata());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build memory extraction prompt", ex);
        }
    }
}
