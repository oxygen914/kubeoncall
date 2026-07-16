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
        Map<String, String> headers = properties.getRag().getEmbeddingApiKey() == null
                        || properties.getRag().getEmbeddingApiKey().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + properties.getRag().getEmbeddingApiKey());
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getEmbeddingEndpoint(),
                Map.of(
                        "model", properties.getRag().getEmbeddingModel(),
                        "input", text == null ? "" : text,
                        "dimensions", properties.getRag().getEmbeddingDimensions()),
                properties.getRag().getEmbeddingTimeoutMillis(),
                headers,
                Map.of("targetSystem", "embedding", "tool", "embedding.embed"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            throw RagProviderException.fromResponse("embedding", response);
        }
        Object body = response.getOrDefault("response", response);
        if (body instanceof Map<?, ?> map) {
            List<Double> openAiEmbedding = openAiEmbedding(map);
            if (!openAiEmbedding.isEmpty()) {
                return validateDimensions(openAiEmbedding);
            }
            return validateDimensions(vectorValue(map.get("embedding")));
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

    private List<Double> openAiEmbedding(Map<?, ?> body) {
        Object data = body.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) {
            return List.of();
        }
        return vectorValue(first.get("embedding"));
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
