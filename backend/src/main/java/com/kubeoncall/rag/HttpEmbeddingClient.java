package com.kubeoncall.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingClient.class);

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public HttpEmbeddingClient(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return properties.getRag().isEmbeddingEnabled()
                && properties.getRag().getEmbeddingEndpoint() != null
                && !properties.getRag().getEmbeddingEndpoint().isBlank();
    }

    @Override
    public List<Double> embed(String text) {
        List<List<Double>> batch = request(text == null ? "" : text, 1);
        return batch.isEmpty() ? List.of() : batch.get(0);
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return request(texts, texts.size());
    }

    private List<List<Double>> request(Object input, int expectedCount) {
        Map<String, String> headers = properties.getRag().getEmbeddingApiKey() == null
                        || properties.getRag().getEmbeddingApiKey().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + properties.getRag().getEmbeddingApiKey());
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getEmbeddingEndpoint(),
                Map.of(
                        "model", properties.getRag().getEmbeddingModel(),
                        "input", input,
                        "dimensions", properties.getRag().getEmbeddingDimensions()),
                properties.getRag().getEmbeddingTimeoutMillis(),
                headers,
                Map.of("targetSystem", "embedding", "tool", "embedding.embed"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            throw RagProviderException.fromResponse("embedding", response);
        }
        Object body = response.getOrDefault("response", response);
        if (body instanceof Map<?, ?> map) {
            List<List<Double>> openAiEmbeddings = openAiEmbeddings(map, expectedCount);
            if (!openAiEmbeddings.isEmpty()) {
                return openAiEmbeddings.stream().map(this::validateDimensions).toList();
            }
            List<Double> legacy = validateDimensions(vectorValue(map.get("embedding")));
            return legacy.isEmpty() ? List.of() : List.of(legacy);
        }
        return List.of();
    }

    @Override
    public String provider() {
        return "http_embedding";
    }

    private List<Double> toDoubles(List<?> raw) {
        List<Double> values = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Number number) {
                values.add(number.doubleValue());
            }
        }
        return values;
    }

    private List<List<Double>> openAiEmbeddings(Map<?, ?> body, int expectedCount) {
        Object data = body.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<List<Double>> ordered = new ArrayList<>(java.util.Collections.nCopies(expectedCount, List.of()));
        int fallbackIndex = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> embeddingItem)) {
                continue;
            }
            int index = embeddingItem.get("index") instanceof Number number ? number.intValue() : fallbackIndex;
            fallbackIndex++;
            if (index >= 0 && index < ordered.size()) {
                ordered.set(index, vectorValue(embeddingItem.get("embedding")));
            }
        }
        return ordered.stream().allMatch(vector -> !vector.isEmpty()) ? List.copyOf(ordered) : List.of();
    }

    private List<Double> vectorValue(Object raw) {
        return raw instanceof List<?> list ? toDoubles(list) : List.of();
    }

    private List<Double> validateDimensions(List<Double> vector) {
        int expected = properties.getRag().getEmbeddingDimensions();
        if (vector.isEmpty() || expected <= 0 || vector.size() == expected) {
            return vector;
        }
        log.warn(
                "Embedding response dimension mismatch; falling back to lexical retrieval: expectedDimensions={}, actualDimensions={}",
                expected,
                vector.size());
        throw new RagProviderException(
                "embedding dimension mismatch: expected=" + expected + ", actual=" + vector.size());
    }
}
