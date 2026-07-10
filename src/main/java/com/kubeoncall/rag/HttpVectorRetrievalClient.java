package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (!available()) {
            return List.of();
        }
        Map<String, Object> response = toolHttpClient.post(
                properties.getRag().getVectorEndpoint(),
                Map.of(
                        "query", request.question() == null ? "" : request.question(),
                        "filters", request.filters() == null ? Map.of() : request.filters(),
                        "topK", candidateSize
                ),
                properties.getRag().getVectorTimeoutMillis(),
                Map.of("targetSystem", "vector-search", "tool", "vector.search")
        );
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return List.of();
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> bodyMap) || !(bodyMap.get("documents") instanceof List<?> docs)) {
            return List.of();
        }
        return docs.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::toDocument)
                .toList();
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
                Instant.now()
        );
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
