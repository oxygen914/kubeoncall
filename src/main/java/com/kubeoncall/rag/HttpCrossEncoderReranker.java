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
        int topN = Math.min(documents.size(), Math.max(1, properties.getRag().getRerankTopN()));
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getCrossEncoderEndpoint(),
                requestBody(query, items, topN),
                properties.getRag().getCrossEncoderTimeoutMillis(),
                headers,
                Map.of("targetSystem", "cross-encoder", "tool", "crossEncoder.rerank"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            throw RagProviderException.fromResponse("cross-encoder", response);
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> bodyMap)) {
            return Map.of();
        }
        Map<?, ?> resultBody = resultBody(bodyMap);
        Map<String, Double> standardResult = parseStandardResults(resultBody.get("results"), documents);
        if (!standardResult.isEmpty()) {
            return standardResult;
        }
        Object scores = resultBody.get("scores");
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

    private Map<String, Object> requestBody(String query, List<String> documents, int topN) {
        String normalizedQuery = query == null ? "" : query;
        if (isDashScopeNativeEndpoint()) {
            return Map.of(
                    "model",
                    properties.getRag().getCrossEncoderModel(),
                    "input",
                    Map.of("query", normalizedQuery, "documents", documents),
                    "parameters",
                    Map.of("top_n", topN, "return_documents", false));
        }
        return Map.of(
                "model",
                properties.getRag().getCrossEncoderModel(),
                "query",
                normalizedQuery,
                "documents",
                documents,
                "top_n",
                topN);
    }

    private boolean isDashScopeNativeEndpoint() {
        String endpoint = properties.getRag().getCrossEncoderEndpoint();
        return endpoint != null && endpoint.contains("dashscope.aliyuncs.com/api/v1/services/rerank/");
    }

    /** DashScope returns the standard result list below an {@code output} object. */
    private Map<?, ?> resultBody(Map<?, ?> response) {
        Object output = response.get("output");
        return output instanceof Map<?, ?> map ? map : response;
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
