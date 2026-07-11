package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrievalHit;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class HybridRetrievalService {

    private final KnowledgeRepository knowledgeRepository;
    private final KubeOnCallProperties properties;
    private final List<VectorRetriever> vectorRetrievers;
    private final KubeOnCallMetricsService metricsService;

    @Autowired
    public HybridRetrievalService(KnowledgeRepository knowledgeRepository,
                                  KubeOnCallProperties properties,
                                  List<VectorRetriever> vectorRetrievers,
                                  KubeOnCallMetricsService metricsService) {
        this.knowledgeRepository = knowledgeRepository;
        this.properties = properties;
        this.vectorRetrievers = vectorRetrievers == null ? List.of() : vectorRetrievers;
        this.metricsService = metricsService;
    }

    public HybridRetrievalService(KnowledgeRepository knowledgeRepository,
                                  KubeOnCallProperties properties,
                                  List<VectorRetriever> vectorRetrievers) {
        this(knowledgeRepository, properties, vectorRetrievers, null);
    }

    public HybridRetrievalService(KnowledgeRepository knowledgeRepository,
                                  KubeOnCallProperties properties) {
        this(knowledgeRepository, properties, List.of(), null);
    }

    public List<KnowledgeDocument> retrieve(RetrievalRequest request) {
        return retrieveWithTrace(request).documents().stream()
                .limit(Math.max(1, request.topK()))
                .toList();
    }

    public RetrievalTrace retrieveWithTrace(RetrievalRequest request) {
        Instant startedAt = Instant.now();
        int topK = Math.max(1, request.topK());
        int policyCandidateTopN = (int) Math.min(200L, Math.max(50L, 10L * topK));
        int lexicalCandidateTopN = Math.min(200,
                Math.max(policyCandidateTopN, properties.getRag().getLexicalCandidateTopN()));
        int vectorCandidateTopN = Math.min(200,
                Math.max(policyCandidateTopN, properties.getRag().getVectorCandidateTopN()));
        int rerankCandidateTopN = Math.max(topK, properties.getRag().getRerankTopN());
        RetrieveMethod method = request.retrieveMethod() == null ? RetrieveMethod.HYBRID : request.retrieveMethod();
        boolean lexicalRequested = method == RetrieveMethod.KEYWORD || method == RetrieveMethod.HYBRID;
        boolean vectorRequested = method == RetrieveMethod.VECTOR || method == RetrieveMethod.HYBRID;

        List<String> reasons = new ArrayList<>();
        Instant lexicalStartedAt = Instant.now();
        List<RetrievalHit> lexicalHits = List.of();
        if (lexicalRequested) {
            lexicalHits = lexicalHits(request, lexicalCandidateTopN);
            reasons.add("Applied lexical retrieval over title/content");
        } else {
            reasons.add("Skipped lexical retrieval for VECTOR mode");
        }
        long lexicalLatencyMs = Duration.between(lexicalStartedAt, Instant.now()).toMillis();
        List<KnowledgeDocument> lexicalCandidates = lexicalHits.stream().map(RetrievalHit::document).toList();

        Instant vectorStartedAt = Instant.now();
        List<RetrievalHit> vectorHits = List.of();
        boolean vectorEnabled = properties.getRag().isVectorEnabled();
        boolean vectorFallback = false;
        String vectorFallbackReason = null;
        String vectorSource = "disabled";
        if (vectorRequested && vectorEnabled) {
            try {
                VectorSearchResult vectorSearch = searchVectorCandidates(request, vectorCandidateTopN);
                vectorHits = vectorSearch.hits();
                vectorSource = vectorSearch.source();
                reasons.add("Applied vector retrieval over content semantics");
            } catch (RuntimeException ex) {
                vectorFallback = true;
                vectorFallbackReason = ex.getMessage();
                vectorSource = "fallback";
                reasons.add("Vector retrieval failed and fell back to lexical");
            }
        } else if (!vectorRequested) {
            vectorSource = "not_requested";
            reasons.add("Skipped vector retrieval for KEYWORD mode");
        } else {
            reasons.add("Vector retrieval disabled by configuration");
        }
        long vectorLatencyMs = Duration.between(vectorStartedAt, Instant.now()).toMillis();
        List<KnowledgeDocument> vectorCandidates = vectorHits.stream().map(RetrievalHit::document).toList();

        if (request.filters() != null && !request.filters().isEmpty()) {
            reasons.add("Applied metadata filters: " + request.filters().keySet());
        }
        reasons.add("Prepared up to " + rerankCandidateTopN + " candidates for rerank; final topK is " + topK);

        Instant fusionStartedAt = Instant.now();
        Map<String, Double> lexicalRanks = rankScores(lexicalHits, properties.getRag().getRrfK());
        Map<String, Double> vectorRanks = rankScores(vectorHits, properties.getRag().getRrfK());

        Map<String, KnowledgeDocument> all = new LinkedHashMap<>();
        lexicalCandidates.forEach(doc -> all.putIfAbsent(doc.id(), doc));
        vectorCandidates.forEach(doc -> all.putIfAbsent(doc.id(), doc));

        List<KnowledgeDocument> fused = all.values().stream()
                .sorted(Comparator
                        .comparingDouble((KnowledgeDocument doc) -> lexicalRanks.getOrDefault(doc.id(), 0.0)
                                + vectorRanks.getOrDefault(doc.id(), 0.0)).reversed()
                        .thenComparing(KnowledgeDocument::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(rerankCandidateTopN)
                .toList();

        if (method == RetrieveMethod.KEYWORD) {
            fused = lexicalCandidates.stream().limit(rerankCandidateTopN).toList();
        } else if (method == RetrieveMethod.VECTOR) {
            fused = vectorCandidates.stream().limit(rerankCandidateTopN).toList();
        } else if (!vectorEnabled || vectorFallback || vectorCandidates.isEmpty()) {
            fused = lexicalCandidates.stream().limit(rerankCandidateTopN).toList();
        } else {
            reasons.add("Applied reciprocal rank fusion");
        }
        long fusionLatencyMs = Duration.between(fusionStartedAt, Instant.now()).toMillis();
        List<RetrievalHit> fusedHits = fusedHits(fused, lexicalRanks, vectorRanks);

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", fused.size());
        diagnostics.put("latencyMs", latencyMs);
        diagnostics.put("lexicalLatencyMs", lexicalLatencyMs);
        diagnostics.put("vectorLatencyMs", vectorLatencyMs);
        diagnostics.put("fusionLatencyMs", fusionLatencyMs);
        diagnostics.put("queryLength", request.question() == null ? 0 : request.question().trim().length());
        diagnostics.put("topK", topK);
        diagnostics.put("policyCandidateTopN", policyCandidateTopN);
        diagnostics.put("rerankCandidateTopN", rerankCandidateTopN);
        diagnostics.put("retrieveMethod", method.name());
        diagnostics.put("includeTrace", request.includeTrace());
        diagnostics.put("filterCount", request.filters() == null ? 0 : request.filters().size());
        diagnostics.put("lexicalCandidateCount", lexicalCandidates.size());
        diagnostics.put("vectorCandidateCount", vectorCandidates.size());
        diagnostics.put("rrfK", properties.getRag().getRrfK());
        diagnostics.put("vectorEnabled", vectorEnabled);
        diagnostics.put("vectorFallback", vectorFallback);
        diagnostics.put("vectorFallbackReason", vectorFallbackReason);
        diagnostics.put("vectorSource", vectorSource);
        diagnostics.put("rankingSource", rankingSource(method, vectorEnabled, vectorFallback, vectorCandidates));
        diagnostics.put("lexicalRankingSource", "repository_hit_order");
        diagnostics.put("vectorRankingSource", vectorSource);
        diagnostics.put("fusedDocumentIds", fused.stream().map(KnowledgeDocument::id).toList());
        diagnostics.put("lexicalDocumentIds", lexicalCandidates.stream().map(KnowledgeDocument::id).toList());
        diagnostics.put("vectorDocumentIds", vectorCandidates.stream().map(KnowledgeDocument::id).toList());
        diagnostics.put("lexicalHits", hitTrace(lexicalHits));
        diagnostics.put("vectorHits", hitTrace(vectorHits));
        diagnostics.put("fusedHits", hitTrace(fusedHits));
        recordMetrics(method, vectorSource, vectorFallback, fused.size(), latencyMs);
        return new RetrievalTrace(fused, reasons, diagnostics);
    }

    private String rankingSource(RetrieveMethod method,
                                 boolean vectorEnabled,
                                 boolean vectorFallback,
                                 List<KnowledgeDocument> vectorCandidates) {
        if (method == RetrieveMethod.KEYWORD) {
            return "lexical_repository_hit_order";
        }
        if (method == RetrieveMethod.VECTOR) {
            return "vector_retriever_order";
        }
        if (!vectorEnabled || vectorFallback || vectorCandidates.isEmpty()) {
            return "lexical_repository_hit_order";
        }
        return "reciprocal_rank_fusion";
    }

    private VectorSearchResult searchVectorCandidates(RetrievalRequest request, int candidateSize) {
        for (VectorRetriever retriever : vectorRetrievers) {
            if (!retriever.available()) {
                continue;
            }
            List<RetrievalHit> hits = retriever.retrieveHits(request, candidateSize);
            if (hits == null) {
                List<KnowledgeDocument> documents = retriever.retrieve(request, candidateSize);
                hits = toHits(documents, "VECTOR");
            }
            return new VectorSearchResult(
                    hits,
                    retriever.source());
        }
        throw new IllegalStateException("No available vector retriever for configured backend");
    }

    private List<RetrievalHit> lexicalHits(RetrievalRequest request, int candidateSize) {
        List<RetrievalHit> hits = knowledgeRepository.searchLexicalHits(request, candidateSize);
        if (hits != null) {
            return hits;
        }
        return toHits(knowledgeRepository.searchLexical(request, candidateSize), "LEXICAL");
    }

    private List<RetrievalHit> toHits(List<KnowledgeDocument> documents, String channel) {
        if (documents == null) {
            return List.of();
        }
        int rank = 1;
        List<RetrievalHit> hits = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            hits.add(new RetrievalHit(document, null, rank++, channel));
        }
        return hits;
    }

    private Map<String, Double> rankScores(List<RetrievalHit> hits, int rrfK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (RetrievalHit hit : hits) {
            scores.putIfAbsent(hit.document().id(), 1.0 / (Math.max(1, rrfK) + Math.max(1, hit.rank())));
        }
        return scores;
    }

    private List<RetrievalHit> fusedHits(List<KnowledgeDocument> documents,
                                         Map<String, Double> lexicalRanks,
                                         Map<String, Double> vectorRanks) {
        List<RetrievalHit> hits = new ArrayList<>();
        int rank = 1;
        for (KnowledgeDocument document : documents) {
            double score = lexicalRanks.getOrDefault(document.id(), 0.0)
                    + vectorRanks.getOrDefault(document.id(), 0.0);
            hits.add(new RetrievalHit(document, score, rank++, "FUSED"));
        }
        return hits;
    }

    private List<Map<String, Object>> hitTrace(List<RetrievalHit> hits) {
        return hits.stream().map(hit -> {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("documentId", hit.document().id());
            trace.put("rawScore", hit.rawScore());
            trace.put("rank", hit.rank());
            trace.put("channel", hit.channel());
            return trace;
        }).toList();
    }

    private void recordMetrics(RetrieveMethod method, String vectorSource, boolean vectorFallback, long resultCount, long latencyMs) {
        if (metricsService != null) {
            metricsService.recordRagRetrieval(method.name(), vectorSource, vectorFallback, resultCount, latencyMs);
        }
    }

    public record RetrievalTrace(
            List<KnowledgeDocument> documents,
            List<String> reasons,
            Map<String, Object> diagnostics
    ) {
    }

    private record VectorSearchResult(List<RetrievalHit> hits, String source) {
    }
}
