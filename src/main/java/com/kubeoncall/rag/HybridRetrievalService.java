package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
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
    private final List<VectorRetrievalClient> vectorRetrievalClients;

    @Autowired
    public HybridRetrievalService(KnowledgeRepository knowledgeRepository,
                                  KubeOnCallProperties properties,
                                  List<VectorRetrievalClient> vectorRetrievalClients) {
        this.knowledgeRepository = knowledgeRepository;
        this.properties = properties;
        this.vectorRetrievalClients = vectorRetrievalClients == null ? List.of() : vectorRetrievalClients;
    }

    public HybridRetrievalService(KnowledgeRepository knowledgeRepository,
                                  KubeOnCallProperties properties) {
        this(knowledgeRepository, properties, List.of());
    }

    public List<KnowledgeDocument> retrieve(RetrievalRequest request) {
        return retrieveWithTrace(request).documents();
    }

    public RetrievalTrace retrieveWithTrace(RetrievalRequest request) {
        Instant startedAt = Instant.now();
        int topK = Math.max(1, request.topK());
        int lexicalCandidateTopN = Math.max(topK, properties.getRag().getLexicalCandidateTopN());
        int vectorCandidateTopN = Math.max(topK, properties.getRag().getVectorCandidateTopN());

        List<KnowledgeDocument> lexicalCandidates = knowledgeRepository.searchLexical(request, lexicalCandidateTopN);
        List<String> reasons = new ArrayList<>();
        reasons.add("Applied lexical retrieval over title/content");

        List<KnowledgeDocument> vectorCandidates = List.of();
        boolean vectorEnabled = properties.getRag().isVectorEnabled();
        boolean vectorFallback = false;
        String vectorFallbackReason = null;
        String vectorSource = "disabled";
        if (vectorEnabled) {
            try {
                vectorCandidates = searchVectorCandidates(request, vectorCandidateTopN);
                vectorSource = vectorRetrievalClients.stream().anyMatch(VectorRetrievalClient::available)
                        ? "external_vector_service"
                        : "repository_compat";
                reasons.add("Applied vector retrieval over content semantics");
            } catch (RuntimeException ex) {
                vectorFallback = true;
                vectorFallbackReason = ex.getMessage();
                vectorSource = "fallback";
                reasons.add("Vector retrieval failed and fell back to lexical");
            }
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

        if (!vectorEnabled || vectorFallback || vectorCandidates.isEmpty()) {
            fused = lexicalCandidates.stream().limit(topK).toList();
        } else {
            reasons.add("Applied reciprocal rank fusion");
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", fused.size());
        diagnostics.put("latencyMs", Duration.between(startedAt, Instant.now()).toMillis());
        diagnostics.put("queryLength", request.question() == null ? 0 : request.question().trim().length());
        diagnostics.put("topK", topK);
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
        return new RetrievalTrace(fused, reasons, diagnostics);
    }

    private List<KnowledgeDocument> searchVectorCandidates(RetrievalRequest request, int candidateSize) {
        for (VectorRetrievalClient client : vectorRetrievalClients) {
            if (!client.available()) {
                continue;
            }
            List<KnowledgeDocument> documents = client.search(request, candidateSize);
            if (documents != null && !documents.isEmpty()) {
                return documents;
            }
        }
        return knowledgeRepository.searchVector(request, candidateSize);
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

    public record RetrievalTrace(
            List<KnowledgeDocument> documents,
            List<String> reasons,
            Map<String, Object> diagnostics
    ) {
    }
}
