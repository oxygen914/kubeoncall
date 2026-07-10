package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class HttpEmbeddingClient implements EmbeddingClient {

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
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getEmbeddingEndpoint(),
                Map.of(
                        "model", properties.getRag().getEmbeddingModel(),
                        "input", text == null ? "" : text
                ),
                properties.getRag().getEmbeddingTimeoutMillis(),
                Map.of("targetSystem", "embedding", "tool", "embedding.embed")
        );
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return List.of();
        }
        Object body = response.getOrDefault("response", response);
        if (body instanceof Map<?, ?> map) {
            List<Double> openAiEmbedding = openAiEmbedding(map);
            if (!openAiEmbedding.isEmpty()) {
                return openAiEmbedding;
            }
            return vectorValue(map.get("embedding"));
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
}
