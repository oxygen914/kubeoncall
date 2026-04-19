package com.kubeoncall.rag;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RerankService {

    public RerankTrace rerank(String query, List<KnowledgeDocument> documents) {
        Instant startedAt = Instant.now();
        Set<String> tokens = tokenize(query);
        List<KnowledgeDocument> reranked = documents.stream()
                .sorted(Comparator
                        .comparingInt((KnowledgeDocument doc) -> score(tokens, doc)).reversed()
                        .thenComparing(KnowledgeDocument::createdAt).reversed())
                .toList();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("rerankLatencyMs", Duration.between(startedAt, Instant.now()).toMillis());
        diagnostics.put("queryTokens", tokens);
        return new RerankTrace(reranked, diagnostics);
    }

    private int score(Set<String> tokens, KnowledgeDocument document) {
        String haystack = (document.title() + " " + document.content()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                score += 3;
            }
            if (document.metadata() != null && document.metadata().values().stream().anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(token))) {
                score += 2;
            }
        }
        return score;
    }

    private Set<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        return List.of(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .stream()
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    public record RerankTrace(
            List<KnowledgeDocument> documents,
            Map<String, Object> diagnostics
    ) {
    }
}
