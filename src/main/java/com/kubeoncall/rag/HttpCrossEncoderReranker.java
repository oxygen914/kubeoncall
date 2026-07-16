package com.kubeoncall.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.tool.http.ToolHttpClient;

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

        List<String> items = documents.stream()
                .map(document -> (document.title() == null ? "" : document.title())
                        + "\n"
                        + (document.content() == null ? "" : document.content()))
                .toList();
        Map<String, String> headers = properties.getRag().getCrossEncoderApiKey() == null
                        || properties.getRag().getCrossEncoderApiKey().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + properties.getRag().getCrossEncoderApiKey());
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getCrossEncoderEndpoint(),
                Map.of(
                        "model",
                        properties.getRag().getCrossEncoderModel(),
                        "query",
                        query == null ? "" : query,
                        "documents",
                        items,
                        "top_n",
                        Math.min(
                                documents.size(),
                                Math.max(1, properties.getRag().getRerankTopN()))),
                properties.getRag().getCrossEncoderTimeoutMillis(),
                headers,
                Map.of("targetSystem", "cross-encoder", "tool", "crossEncoder.rerank"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return Map.of();
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> bodyMap)) {
            return Map.of();
        }
        Map<String, Double> standardResult = parseStandardResults(bodyMap.get("results"), documents);
        if (!standardResult.isEmpty()) {
            return standardResult;
        }
        Object scores = bodyMap.get("scores");
        Map<String, Double> result = new LinkedHashMap<>();
        if (scores instanceof Map<?, ?> scoreMap) {
            scoreMap.forEach((key, value) -> {
                if (value instanceof Number number) {
                    result.put(String.valueOf(key), number.doubleValue());
                }
            });
        }
        return result;
    }

    private Map<String, Double> parseStandardResults(Object rawResults, List<KnowledgeDocument> documents) {
        if (!(rawResults instanceof List<?> results)) {
            return Map.of();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Object raw : results) {
            if (!(raw instanceof Map<?, ?> item)
                    || !(item.get("index") instanceof Number index)
                    || !(item.get("relevance_score") instanceof Number score)) {
                continue;
            }
            int position = index.intValue();
            if (position >= 0 && position < documents.size()) {
                scores.put(documents.get(position).id(), score.doubleValue());
            }
        }
        return scores;
    }
}
