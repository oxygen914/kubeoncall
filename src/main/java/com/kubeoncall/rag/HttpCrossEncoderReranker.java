package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HttpCrossEncoderReranker implements CrossEncoderReranker {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public HttpCrossEncoderReranker(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return properties.getRag().isCrossEncoderEnabled()
                && properties.getRag().getCrossEncoderEndpoint() != null
                && !properties.getRag().getCrossEncoderEndpoint().isBlank();
    }

    @Override
    public Map<String, Double> score(String query, List<KnowledgeDocument> documents) {
        if (!available() || documents == null || documents.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> items = documents.stream()
                .map(document -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", document.id());
                    item.put("title", document.title());
                    item.put("content", document.content());
                    item.put("metadata", document.metadata());
                    return item;
                })
                .toList();
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getCrossEncoderEndpoint(),
                Map.of("query", query == null ? "" : query, "documents", items),
                properties.getRag().getCrossEncoderTimeoutMillis(),
                Map.of("targetSystem", "cross-encoder", "tool", "crossEncoder.rerank")
        );
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return Map.of();
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> bodyMap)) {
            return Map.of();
        }
        Object scores = bodyMap.get("scores");
        if (!(scores instanceof Map<?, ?> scoreMap)) {
            return Map.of();
        }

        Map<String, Double> result = new LinkedHashMap<>();
        scoreMap.forEach((key, value) -> {
            if (value instanceof Number number) {
                result.put(String.valueOf(key), number.doubleValue());
            }
        });
        return result;
    }
}
