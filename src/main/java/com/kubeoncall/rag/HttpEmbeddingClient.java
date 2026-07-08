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
                Map.of("text", text == null ? "" : text),
                properties.getRag().getEmbeddingTimeoutMillis(),
                Map.of("targetSystem", "embedding", "tool", "embedding.embed")
        );
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return List.of();
        }
        Object body = response.getOrDefault("response", response);
        if (body instanceof Map<?, ?> map) {
            Object embedding = map.get("embedding");
            if (embedding instanceof List<?> list) {
                return toDoubles(list);
            }
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
}
