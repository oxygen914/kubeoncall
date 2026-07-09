package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.domain.rag.RetrievalRequest;
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
        return retrieveWithTrace(request).documents();
    }

    public RetrievalTrace retrieveWithTrace(RetrievalRequest request) {
        Instant startedAt = Instant.now();
        int topK = Math.max(1, request.topK());
        int lexicalCandidateTopN = Math.max(topK, properties.getRag().getLexicalCandidateTopN());
        int vectorCandidateTopN = Math.max(topK, properties.getRag().getVectorCandidateTopN());
        RetrieveMethod method = request.retrieveMethod() == null ? RetrieveMethod.HYBRID : request.retrieveMethod();
        boolean lexicalRequested = method == RetrieveMethod.KEYWORD || method == RetrieveMethod.HYBRID;
        boolean vectorRequested = method == RetrieveMethod.VECTOR || method == RetrieveMethod.HYBRID;

        List<String> reasons = new ArrayList<>();
        List<KnowledgeDocument> lexicalCandidates = List.of();
        if (lexicalRequested) {
            lexicalCandidates = knowledgeRepository.searchLexical(request, lexicalCandidateTopN);
            reasons.add("Applied lexical retrieval over title/content");
        } else {
            reasons.add("Skipped lexical retrieval for VECTOR mode");
        }

        List<KnowledgeDocument> vectorCandidates = List.of();
        boolean vectorEnabled = properties.getRag().isVectorEnabled();
        boolean vectorFallback = false;
        String vectorFallbackReason = null;
        String vectorSource = "disabled";
        if (vectorRequested && vectorEnabled) {
            try {
                vectorCandidates = searchVectorCandidates(request, vectorCandidateTopN);
                vectorSource = vectorSource();
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

        if (request.filters() != null && !request.filters().isEmpty()) {
            reasons.add("Applied metadata filters: " + request.filters().keySet());
        }
        reasons.add("Limited topK to " + topK);

        Map<String, Double> lexicalRanks = rankScores(lexicalCandidates, properties.getRag().getRrfK());
        Map<String, Double> vectorRanks = rankScores(vectorCandidates, properties.getRag().getRrfK());

        Map<String, KnowledgeDocument> all = new LinkedHashMap<>();
        lexicalCandidates.forEach(doc -> all.putIfAbsent(doc.id(), doc));
        vectorCandidates.forEach(doc -> all.putIfAbsent(doc.id(), doc));

        List<KnowledgeDocument> fused = all.values().stream()
                .sorted(Comparator
                        .comparingDouble((KnowledgeDocument doc) -> lexicalRanks.getOrDefault(doc.id(), 0.0)
                                + vectorRanks.getOrDefault(doc.id(), 0.0)).reversed()
                        .thenComparing(KnowledgeDocument::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .toList();

        if (method == RetrieveMethod.KEYWORD) {
            fused = lexicalCandidates.stream().limit(topK).toList();
        } else if (method == RetrieveMethod.VECTOR) {
            fused = vectorCandidates.stream().limit(topK).toList();
        } else if (!vectorEnabled || vectorFallback || vectorCandidates.isEmpty()) {
            fused = lexicalCandidates.stream().limit(topK).toList();
        } else {
            reasons.add("Applied reciprocal rank fusion");
        }

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", fused.size());
        diagnostics.put("latencyMs", latencyMs);
        diagnostics.put("queryLength", request.question() == null ? 0 : request.question().trim().length());
        diagnostics.put("topK", topK);
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
        diagnostics.put("fusedDocumentIds", fused.stream().map(KnowledgeDocument::id).toList());
        diagnostics.put("lexicalDocumentIds", lexicalCandidates.stream().map(KnowledgeDocument::id).toList());
        diagnostics.put("vectorDocumentIds", vectorCandidates.stream().map(KnowledgeDocument::id).toList());
        recordMetrics(method, vectorSource, vectorFallback, fused.size(), latencyMs);
        return new RetrievalTrace(fused, reasons, diagnostics);
    }

    private List<KnowledgeDocument> searchVectorCandidates(RetrievalRequest request, int candidateSize) {
        for (VectorRetriever retriever : vectorRetrievers) {
            if (!retriever.available()) {
                continue;
            }
            List<KnowledgeDocument> documents = retriever.retrieve(request, candidateSize);
            if (documents != null && !documents.isEmpty()) {
                return documents;
            }
        }
        return knowledgeRepository.searchVector(request, candidateSize);
    }

    private String vectorSource() {
        return vectorRetrievers.stream()
                .filter(VectorRetriever::available)
                .findFirst()
                .map(VectorRetriever::source)
                .orElse("repository_compat");
    }

    private Map<String, Double> rankScores(List<KnowledgeDocument> documents, int rrfK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        int rank = 1;
        for (KnowledgeDocument document : new LinkedHashSet<>(documents)) {
            scores.put(document.id(), 1.0 / (Math.max(1, rrfK) + rank));
            rank++;
        }
        return scores;
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
}
