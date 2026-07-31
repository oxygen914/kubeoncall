package com.kubeoncall.rag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalHit;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class HttpVectorRetrievalClient implements VectorRetrievalClient {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public HttpVectorRetrievalClient(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return properties.getRag().isVectorEnabled()
                && "external".equalsIgnoreCase(properties.getRag().getVectorBackend())
                && properties.getRag().getVectorEndpoint() != null
                && !properties.getRag().getVectorEndpoint().isBlank();
    }

    @Override
    public List<KnowledgeDocument> search(RetrievalRequest request, int candidateSize) {
        return retrieveHits(request, candidateSize).stream()
                .map(RetrievalHit::document)
                .toList();
    }

    @Override
    public List<RetrievalHit> retrieveHits(RetrievalRequest request, int candidateSize) {
        if (!available()) {
            return List.of();
        }
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getVectorEndpoint(),
                Map.of(
                        "query", request.question() == null ? "" : request.question(),
                        "filters", request.filters() == null ? Map.of() : request.filters(),
                        "topK", candidateSize),
                properties.getRag().getVectorTimeoutMillis(),
                Map.of("targetSystem", "vector-search", "tool", "vector.search"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return List.of();
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> bodyMap) || !(bodyMap.get("documents") instanceof List<?> docs)) {
            return List.of();
        }
        List<RetrievalHit> hits = new java.util.ArrayList<>();
        int position = 1;
        for (Object item : docs) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            int rank = raw.get("rank") instanceof Number number ? number.intValue() : position;
            Double score = score(raw);
            hits.add(new RetrievalHit(toDocument(raw), score, rank, "EXTERNAL_VECTOR"));
            position++;
        }
        return hits;
    }

    private Double score(Map<?, ?> raw) {
        Object value = raw.containsKey("score") ? raw.get("score") : raw.get("_score");
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private KnowledgeDocument toDocument(Map<?, ?> raw) {
        Map<String, String> metadata = new LinkedHashMap<>();
        Object rawMetadata = raw.get("metadata");
        if (rawMetadata instanceof Map<?, ?> metadataMap) {
            metadataMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    metadata.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return new KnowledgeDocument(
                stringValue(raw.get("id")),
                stringValue(raw.get("title")),
                stringValue(raw.get("content")),
                stringValue(raw.containsKey("source") ? raw.get("source") : "vector"),
                metadata,
                Instant.now());
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
