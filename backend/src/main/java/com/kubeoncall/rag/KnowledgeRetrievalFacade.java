package com.kubeoncall.rag;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.repository.KnowledgeRepository;

@Service
public class KnowledgeRetrievalFacade {

    private final KnowledgeRepository knowledgeRepository;
    private final QueryRewriteService queryRewriteService;
    private final RagRouter ragRouter;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final KubeOnCallProperties properties;

    public KnowledgeRetrievalFacade(
            KnowledgeRepository knowledgeRepository,
            QueryRewriteService queryRewriteService,
            RagRouter ragRouter,
            HybridRetrievalService hybridRetrievalService,
            RerankService rerankService,
            KubeOnCallProperties properties) {
        this.knowledgeRepository = knowledgeRepository;
        this.queryRewriteService = queryRewriteService;
        this.ragRouter = ragRouter;
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.properties = properties;
    }

    public RetrievalResult retrieve(String question, Map<String, String> filters) {
        return retrieve(question, filters, null, RetrieveMethod.HYBRID, false);
    }

    public RetrievalResult retrieve(
            String question,
            Map<String, String> filters,
            Integer topK,
            RetrieveMethod retrieveMethod,
            boolean includeTrace) {
        String rewritten = queryRewriteService.rewrite(question);
        String route = ragRouter.route(rewritten);
        int effectiveTopK = topK == null || topK <= 0 ? properties.getRag().getDefaultTopK() : topK;
        RetrievalRequest request = new RetrievalRequest(
                rewritten,
                filters,
                effectiveTopK,
                retrieveMethod == null ? RetrieveMethod.HYBRID : retrieveMethod,
                includeTrace);
        HybridRetrievalService.RetrievalTrace retrievalTrace = hybridRetrievalService.retrieveWithTrace(request);
        MemoryFilterResult memoryFilter = filterMemoryDocuments(retrievalTrace.documents(), filters);
        Instant rerankStartedAt = Instant.now();
        RerankService.RerankTrace rerankTrace = rerankService.rerank(rewritten, memoryFilter.documents());
        long rerankLatencyMs = Duration.between(rerankStartedAt, Instant.now()).toMillis();

        List<KnowledgeDocument> finalCandidates =
                rerankTrace.documents().stream().limit(effectiveTopK).toList();
        Instant parentAggregationStartedAt = Instant.now();
        ParentAggregation parentAggregation = aggregateParents(finalCandidates);
        long parentAggregationLatencyMs =
                Duration.between(parentAggregationStartedAt, Instant.now()).toMillis();
        List<KnowledgeDocument> documents = parentAggregation.documents();

        List<String> reasons = new ArrayList<>(retrievalTrace.reasons());
        if (memoryFilter.excludedCount() > 0) {
            reasons.add("Excluded long-term memory documents from default knowledge retrieval");
        }
        reasons.add(
                documents.isEmpty()
                        ? "No document passed rerank stage"
                        : "Reranked documents by token overlap and metadata match");
        if (parentAggregation.parentLookupCount() > 0) {
            reasons.add("Aggregated parent documents from child chunks");
        }

        Map<String, Object> diagnostics = includeTrace
                ? buildDiagnostics(
                        question,
                        rewritten,
                        filters,
                        route,
                        effectiveTopK,
                        documents,
                        retrievalTrace,
                        rerankTrace,
                        parentAggregation,
                        memoryFilter,
                        rerankLatencyMs,
                        parentAggregationLatencyMs)
                : Map.of();

        String summary = documents.isEmpty()
                ? "No matching knowledge found"
                : "Retrieved " + documents.size() + " documents via " + route;
        return new RetrievalResult(rewritten, documents, route, summary, reasons, diagnostics);
    }

    private Map<String, Object> buildDiagnostics(
            String question,
            String rewritten,
            Map<String, String> filters,
            String route,
            int effectiveTopK,
            List<KnowledgeDocument> documents,
            HybridRetrievalService.RetrievalTrace retrievalTrace,
            RerankService.RerankTrace rerankTrace,
            ParentAggregation parentAggregation,
            MemoryFilterResult memoryFilter,
            long rerankLatencyMs,
            long parentAggregationLatencyMs) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(retrievalTrace.diagnostics());
        diagnostics.putAll(rerankTrace.diagnostics());
        diagnostics.put("rawQuery", question == null ? "" : question);
        diagnostics.put("rewrittenQuery", rewritten);
        diagnostics.put("filters", filters == null ? Map.of() : filters);
        diagnostics.put("route", route);
        diagnostics.put("resultCount", documents.size());
        diagnostics.put("finalTopK", effectiveTopK);
        diagnostics.put("parentAggregationApplied", parentAggregation.parentLookupCount() > 0);
        diagnostics.put("parentLookupCount", parentAggregation.parentLookupCount());
        diagnostics.put("childChunkCount", parentAggregation.childChunkCount());
        diagnostics.put("memorySearchExplicit", memoryFilter.memorySearchExplicit());
        diagnostics.put("memoryDocumentsExcluded", memoryFilter.excludedCount());
        diagnostics.put("rerankLatencyMs", rerankLatencyMs);
        diagnostics.put("parentAggregationLatencyMs", parentAggregationLatencyMs);
        return diagnostics;
    }

    private MemoryFilterResult filterMemoryDocuments(List<KnowledgeDocument> documents, Map<String, String> filters) {
        if (documents == null || documents.isEmpty()) {
            return new MemoryFilterResult(List.of(), 0, isMemorySearch(filters));
        }
        boolean memorySearch = isMemorySearch(filters);
        if (memorySearch) {
            return new MemoryFilterResult(documents, 0, true);
        }
        List<KnowledgeDocument> filtered = documents.stream()
                .filter(document -> !isMemoryDocument(document))
                .toList();
        return new MemoryFilterResult(filtered, documents.size() - filtered.size(), false);
    }

    private boolean isMemorySearch(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return false;
        }
        String sourceType = filters.get("source_type");
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = filters.get("sourceType");
        }
        return sourceType != null && "memory".equalsIgnoreCase(sourceType.trim());
    }

    private boolean isMemoryDocument(KnowledgeDocument document) {
        if (document == null) {
            return false;
        }
        if ("memory".equalsIgnoreCase(document.source())) {
            return true;
        }
        Map<String, String> metadata = document.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        String sourceType = metadata.get("source_type");
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = metadata.get("sourceType");
        }
        return sourceType != null && "memory".equalsIgnoreCase(sourceType.trim());
    }

    private ParentAggregation aggregateParents(List<KnowledgeDocument> reranked) {
        List<String> parentIds = reranked.stream()
                .map(KnowledgeDocument::metadata)
                .filter(metadata -> metadata != null
                        && (metadata.containsKey("parentDocumentId") || metadata.containsKey("parent_document_id")))
                .map(this::parentId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        if (parentIds.isEmpty()) {
            return new ParentAggregation(reranked, 0, 0);
        }

        Map<String, KnowledgeDocument> parents = knowledgeRepository.loadParents(parentIds);
        LinkedHashMap<String, KnowledgeDocument> merged = new LinkedHashMap<>();
        int childChunks = 0;
        for (KnowledgeDocument doc : reranked) {
            String parentId = doc.metadata() == null ? null : parentId(doc.metadata());
            if (parentId != null && !parentId.isBlank()) {
                childChunks++;
                KnowledgeDocument parent = parents.get(parentId);
                if (parent != null) {
                    merged.putIfAbsent(parent.id(), parent);
                    continue;
                }
            }
            merged.putIfAbsent(doc.id(), doc);
        }

        return new ParentAggregation(
                new ArrayList<>(new LinkedHashSet<>(merged.values())), parentIds.size(), childChunks);
    }

    private String parentId(Map<String, String> metadata) {
        String parentId = metadata.get("parentDocumentId");
        return parentId == null || parentId.isBlank() ? metadata.get("parent_document_id") : parentId;
    }

    private record ParentAggregation(List<KnowledgeDocument> documents, int parentLookupCount, int childChunkCount) {}

    private record MemoryFilterResult(
            List<KnowledgeDocument> documents, int excludedCount, boolean memorySearchExplicit) {}
}
