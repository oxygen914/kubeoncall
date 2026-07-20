package com.kubeoncall.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.tool.http.ToolHttpClient;

/** Generates vector-only knowledge expansion while preserving the original lexical content. */
@Service
public class KnowledgeAugmentationService {

    private final ToolHttpClient toolHttpClient;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public KnowledgeAugmentationService(
            ToolHttpClient toolHttpClient, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public KnowledgeDocument augment(KnowledgeDocument chunk) {
        if (chunk == null || !properties.getRag().isAugmentationEnabled()) {
            return chunk;
        }
        Map<String, String> metadata = new LinkedHashMap<>(chunk.metadata() == null ? Map.of() : chunk.metadata());
        metadata.put("augmentation_model", properties.getRag().getAugmentationModel());
        metadata.put("augmentation_version", properties.getRag().getAugmentationVersion());
        Map<String, Object> response;
        try {
            response = invoke(chunk);
        } catch (RuntimeException ex) {
            return failed(chunk, metadata, ex.getClass().getSimpleName());
        }
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return failed(chunk, metadata, String.valueOf(response.getOrDefault("errorType", "request_failed")));
        }
        Optional<Augmentation> parsed = parse(response.get("response"));
        if (parsed.isEmpty()) {
            return failed(chunk, metadata, "invalid_response");
        }
        Augmentation augmentation = parsed.get();
        metadata.put("augmentation_status", "ready");
        put(metadata, "augmentation_summary", augmentation.summary());
        put(metadata, "augmentation_questions", String.join(" | ", augmentation.questions()));
        put(metadata, "augmentation_topics", String.join(",", augmentation.topics()));
        put(metadata, "difficulty", augmentation.difficulty());
        put(metadata, "content_type", augmentation.contentType());
        return new KnowledgeDocument(
                chunk.id(),
                chunk.title(),
                chunk.content(),
                chunk.source(),
                metadata,
                chunk.createdAt(),
                embeddingText(chunk, augmentation),
                chunk.embedding());
    }

    public List<KnowledgeDocument> augmentAll(List<KnowledgeDocument> chunks) {
        if (chunks == null || chunks.isEmpty() || !properties.getRag().isAugmentationEnabled()) {
            return chunks == null ? List.of() : List.copyOf(chunks);
        }
        int concurrency = Math.max(1, properties.getRag().getAugmentationConcurrency());
        if (concurrency == 1 || chunks.size() == 1) {
            return chunks.stream().map(this::augment).toList();
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, chunks.size()));
        try {
            List<Future<KnowledgeDocument>> futures = chunks.stream()
                    .map(chunk -> executor.submit(() -> augment(chunk)))
                    .toList();
            List<KnowledgeDocument> results = new ArrayList<>(chunks.size());
            for (Future<KnowledgeDocument> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception ex) {
                    results.add(chunks.get(results.size()));
                }
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> invoke(KnowledgeDocument chunk) {
        String apiKey = properties.getRag().getAugmentationApiKey();
        Map<String, String> headers =
                apiKey == null || apiKey.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey.trim());
        return toolHttpClient.post(
                properties.getRag().getAugmentationEndpoint(),
                Map.of(
                        "model",
                        properties.getRag().getAugmentationModel(),
                        "temperature",
                        0,
                        "messages",
                        List.of(
                                Map.of("role", "system", "content", systemPrompt()),
                                Map.of("role", "user", "content", userPrompt(chunk)))),
                properties.getRag().getAugmentationTimeoutMillis(),
                headers,
                Map.of("targetSystem", "rag-augmentation", "tool", "knowledge.augment"));
    }

    private String systemPrompt() {
        return """
                Analyze the operational knowledge chunk and return one compact JSON object only.
                Fields: summary (string), questions (array of likely user questions), topics
                (array of short lowercase tags), difficulty (basic/intermediate/advanced), and
                content_type (runbook/reference/troubleshooting/concept). Do not invent facts,
                commands, credentials, endpoints, or numeric thresholds absent from the chunk.
                Keep summary under 500 characters, questions at most 5, and topics at most 8.
                """;
    }

    private String userPrompt(KnowledgeDocument chunk) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", normalized(chunk.title()));
            payload.put(
                    "headingPath",
                    chunk.metadata() == null ? "" : normalized(chunk.metadata().get("heading_path")));
            payload.put("content", normalized(chunk.content()));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build knowledge augmentation prompt", ex);
        }
    }

    private Optional<Augmentation> parse(Object rawResponse) {
        String content = responseContent(rawResponse);
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(content));
            Augmentation augmentation = new Augmentation(
                    truncate(text(root, "summary"), 500),
                    list(root, "questions", 5, 300),
                    list(root, "topics", 8, 80),
                    truncate(text(root, "difficulty"), 32),
                    truncate(text(root, "content_type"), 64));
            return augmentation.empty() ? Optional.empty() : Optional.of(augmentation);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String responseContent(Object response) {
        if (!(response instanceof Map<?, ?> map)) {
            return response == null ? null : String.valueOf(response);
        }
        if (map.get("output_text") != null) {
            return String.valueOf(map.get("output_text"));
        }
        Object choices = map.get("choices");
        if (choices instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") != null) {
            return String.valueOf(message.get("content"));
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> list(JsonNode root, String field, int limit, int maxChars) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            String normalized = truncate(item.asText(), maxChars);
            if (!normalized.isBlank() && !values.contains(normalized)) {
                values.add(normalized);
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return List.copyOf(values);
    }

    private KnowledgeDocument failed(KnowledgeDocument chunk, Map<String, String> metadata, String error) {
        metadata.put("augmentation_status", "failed");
        metadata.put("augmentation_error", normalized(error));
        return new KnowledgeDocument(
                chunk.id(),
                chunk.title(),
                chunk.content(),
                chunk.source(),
                metadata,
                chunk.createdAt(),
                chunk.embeddingText(),
                chunk.embedding());
    }

    private String embeddingText(KnowledgeDocument chunk, Augmentation augmentation) {
        StringBuilder text = new StringBuilder(normalized(chunk.content()));
        append(text, "Summary", augmentation.summary());
        append(text, "Likely questions", String.join("; ", augmentation.questions()));
        append(text, "Topics", String.join(", ", augmentation.topics()));
        return text.toString().trim();
    }

    private void append(StringBuilder target, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.append("\n\n").append(label).append(": ").append(value);
    }

    private void put(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? "" : normalized(value.asText());
    }

    private String truncate(String value, int maxChars) {
        String normalized = normalized(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars - 3) + "...";
    }

    private String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private record Augmentation(
            String summary, List<String> questions, List<String> topics, String difficulty, String contentType) {
        private boolean empty() {
            return summary.isBlank() && questions.isEmpty() && topics.isEmpty();
        }
    }
}
