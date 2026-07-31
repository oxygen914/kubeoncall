package com.kubeoncall.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class HttpMemoryStructuredExtractionClient {

    private final ToolHttpClient toolHttpClient;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public HttpMemoryStructuredExtractionClient(
            ToolHttpClient toolHttpClient, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<List<MemoryExtractionCandidate>> extract(String systemPrompt, String userPrompt) {
        if (!available()) {
            return Optional.empty();
        }
        Map<String, String> headers = properties.getMemory().getLlmExtractionApiKey() == null
                        || properties.getMemory().getLlmExtractionApiKey().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + properties.getMemory().getLlmExtractionApiKey());
        Map<String, Object> response = toolHttpClient.post(
                properties.getMemory().getLlmExtractionEndpoint(),
                Map.of(
                        "model",
                        properties.getMemory().getLlmExtractionModel(),
                        "temperature",
                        0,
                        "messages",
                        List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt))),
                properties.getMemory().getLlmExtractionTimeoutMillis(),
                headers,
                Map.of("targetSystem", "memory-llm", "tool", "memory.extract"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return Optional.empty();
        }
        String content = responseContent(response.get("response"));
        return content == null || content.isBlank() ? Optional.empty() : parse(content);
    }

    private boolean available() {
        return properties.getMemory().isLlmExtractionEnabled()
                && properties.getMemory().getLlmExtractionEndpoint() != null
                && !properties.getMemory().getLlmExtractionEndpoint().isBlank();
    }

    private String responseContent(Object response) {
        if (!(response instanceof Map<?, ?> map)) {
            return response == null ? null : String.valueOf(response);
        }
        Object outputText = map.get("output_text");
        if (outputText != null) {
            return String.valueOf(outputText);
        }
        Object choices = map.get("choices");
        if (choices instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") != null) {
            return String.valueOf(message.get("content"));
        }
        return map.containsKey("memories") ? stringify(map) : null;
    }

    private String stringify(Map<?, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            return null;
        }
    }

    private Optional<List<MemoryExtractionCandidate>> parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(content));
            JsonNode memories = root.path("memories");
            if (!memories.isArray()) {
                return Optional.empty();
            }
            List<MemoryExtractionCandidate> candidates = new ArrayList<>();
            for (JsonNode item : memories) {
                MemoryType type = enumValue(MemoryType.class, text(item, "memoryType"));
                MemoryScope scope = enumValue(MemoryScope.class, text(item, "scope"));
                String candidateContent = text(item, "content");
                if (type == null || scope == null || candidateContent == null || candidateContent.isBlank()) {
                    continue;
                }
                List<String> evidence = new ArrayList<>();
                if (item.path("evidence").isArray()) {
                    item.path("evidence").forEach(node -> evidence.add(node.asText()));
                }
                candidates.add(new MemoryExtractionCandidate(
                        type,
                        scope,
                        text(item, "subject"),
                        candidateContent,
                        text(item, "service"),
                        text(item, "resource"),
                        text(item, "fingerprint"),
                        evidence,
                        item.path("confidence").isNumber()
                                ? item.path("confidence").asDouble()
                                : null));
            }
            return candidates.isEmpty() && !memories.isEmpty()
                    ? Optional.empty()
                    : Optional.of(List.copyOf(candidates));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
