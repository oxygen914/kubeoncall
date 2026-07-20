package com.kubeoncall.rag;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final KubeOnCallProperties properties;
    private final List<CrossEncoderReranker> crossEncoderRerankers;
    private final KubeOnCallMetricsService metricsService;

    public RerankService(
            KubeOnCallProperties properties,
            List<CrossEncoderReranker> crossEncoderRerankers,
            KubeOnCallMetricsService metricsService) {
        this.properties = properties;
        this.crossEncoderRerankers = List.copyOf(crossEncoderRerankers);
        this.metricsService = metricsService;
    }

    public RerankTrace rerank(String query, List<KnowledgeDocument> documents) {
        Instant startedAt = Instant.now();
        Set<String> tokens = tokenize(query);
        int rerankTopN = Math.max(1, properties.getRag().getRerankTopN());
        List<KnowledgeDocument> candidates = documents == null
                ? List.of()
                : documents.stream().limit(rerankTopN).toList();
        boolean crossEncoderEnabled = properties.getRag().isCrossEncoderEnabled();
        CrossEncoderAttempt crossEncoderAttempt = crossEncoderEnabled
                ? crossEncoderScores(query, candidates)
                : new CrossEncoderAttempt(Map.of(), "Cross-encoder disabled by configuration");
        Map<String, Double> crossEncoderScores = crossEncoderAttempt.scores();
        boolean crossEncoderApplied = !crossEncoderScores.isEmpty();

        Map<String, Integer> scoreMap = new LinkedHashMap<>();
        List<KnowledgeDocument> ranked;
        if (crossEncoderApplied) {
            ranked = candidates.stream()
                    .sorted(Comparator.comparingDouble((KnowledgeDocument doc) ->
                                    crossEncoderScores.getOrDefault(doc.id(), Double.NEGATIVE_INFINITY))
                            .reversed())
                    .toList();
        } else if (crossEncoderEnabled) {
            ranked = candidates;
        } else {
            ranked = candidates.stream()
                    .sorted(Comparator.comparingInt((KnowledgeDocument doc) -> score(tokens, doc))
                            .reversed()
                            .thenComparing(
                                    KnowledgeDocument::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }
        for (KnowledgeDocument document : ranked) {
            scoreMap.put(document.id(), score(tokens, document));
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put(
                "rerankLatencyMs", Duration.between(startedAt, Instant.now()).toMillis());
        diagnostics.put("queryTokens", tokens);
        diagnostics.put(
                "rerankInputDocumentIds",
                candidates.stream().map(KnowledgeDocument::id).toList());
        diagnostics.put(
                "rerankedDocumentIds",
                ranked.stream().map(KnowledgeDocument::id).toList());
        diagnostics.put("rerankCandidateCount", candidates.size());
        diagnostics.put("scoreByDocument", scoreMap);
        diagnostics.put("crossEncoderScoreByDocument", crossEncoderScores);
        diagnostics.put("crossEncoderEnabled", crossEncoderEnabled);
        diagnostics.put("crossEncoderApplied", crossEncoderApplied);
        diagnostics.put("crossEncoderFallback", crossEncoderEnabled && !crossEncoderApplied);
        diagnostics.put(
                "crossEncoderFallbackReason",
                crossEncoderEnabled
                        ? (crossEncoderApplied ? null : crossEncoderAttempt.fallbackReason())
                        : "Cross-encoder disabled by configuration");
        diagnostics.put(
                "rerankStrategy",
                crossEncoderApplied
                        ? "cross_encoder"
                        : (crossEncoderEnabled ? "retrieval_order_fallback" : "rule_overlap"));
        diagnostics.put("rankTrace", rankTrace(ranked, scoreMap, crossEncoderScores));
        diagnostics.put("rerankTopN", rerankTopN);
        metricsService.recordRagRerank(
                crossEncoderEnabled, crossEncoderApplied, crossEncoderEnabled && !crossEncoderApplied);
        return new RerankTrace(ranked, diagnostics);
    }

    private List<Map<String, Object>> rankTrace(
            List<KnowledgeDocument> ranked, Map<String, Integer> ruleScores, Map<String, Double> crossEncoderScores) {
        int rank = 1;
        java.util.ArrayList<Map<String, Object>> trace = new java.util.ArrayList<>();
        for (KnowledgeDocument document : ranked) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", rank++);
            item.put("documentId", document.id());
            item.put("ruleScore", ruleScores.getOrDefault(document.id(), 0));
            if (crossEncoderScores.containsKey(document.id())) {
                item.put("crossEncoderScore", crossEncoderScores.get(document.id()));
            }
            trace.add(item);
        }
        return trace;
    }

    private CrossEncoderAttempt crossEncoderScores(String query, List<KnowledgeDocument> documents) {
        for (CrossEncoderReranker reranker : crossEncoderRerankers) {
            if (!reranker.available()) {
                continue;
            }
            try {
                Map<String, Double> scores = reranker.score(query, documents);
                if (scores != null && !scores.isEmpty()) {
                    return new CrossEncoderAttempt(scores, null);
                }
                return new CrossEncoderAttempt(Map.of(), "Cross-encoder returned no scores; preserved retrieval order");
            } catch (RuntimeException ex) {
                log.warn(
                        "Cross-encoder rerank failed; preserving retrieval order: adapter={}, errorType={}",
                        reranker.getClass().getSimpleName(),
                        ex.getClass().getSimpleName());
                String reason = ex instanceof RagProviderException
                        ? ex.getMessage()
                        : "Cross-encoder adapter failed: errorType="
                                + ex.getClass().getSimpleName();
                return new CrossEncoderAttempt(Map.of(), reason + "; preserved retrieval order");
            }
        }
        return new CrossEncoderAttempt(Map.of(), "No available cross-encoder adapter; preserved retrieval order");
    }

    private int score(Set<String> tokens, KnowledgeDocument document) {
        String title = document.title() == null ? "" : document.title();
        String content = document.content() == null ? "" : document.content();
        String haystack = (title + " " + content).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                score += 3;
            }
            if (document.metadata() != null
                    && document.metadata().values().stream()
                            .anyMatch(value -> value != null
                                    && value.toLowerCase(Locale.ROOT).contains(token))) {
                score += 2;
            }
        }
        return score;
    }

    private Set<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_-]+", " ");
        return List.of(normalized.split("\\s+")).stream()
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    public record RerankTrace(List<KnowledgeDocument> documents, Map<String, Object> diagnostics) {}

    private record CrossEncoderAttempt(Map<String, Double> scores, String fallbackReason) {}
}
