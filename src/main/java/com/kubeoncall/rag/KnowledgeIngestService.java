package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeIngestService {

    private final KnowledgeRepository knowledgeRepository;
    private final QueryRewriteService queryRewriteService;
    private final RagRouter ragRouter;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final KubeOnCallProperties properties;
    private final KnowledgeChunker knowledgeChunker;
    private final KnowledgeObjectStorageService knowledgeObjectStorageService;

    public KnowledgeIngestService(KnowledgeRepository knowledgeRepository,
                                  QueryRewriteService queryRewriteService,
                                  RagRouter ragRouter,
                                  HybridRetrievalService hybridRetrievalService,
                                  RerankService rerankService,
                                  KubeOnCallProperties properties,
                                  KnowledgeChunker knowledgeChunker,
                                  KnowledgeObjectStorageService knowledgeObjectStorageService) {
        this.knowledgeRepository = knowledgeRepository;
        this.queryRewriteService = queryRewriteService;
        this.ragRouter = ragRouter;
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.properties = properties;
        this.knowledgeChunker = knowledgeChunker;
        this.knowledgeObjectStorageService = knowledgeObjectStorageService;
    }

    public KnowledgeDocument ingest(String title, String content, String source, Map<String, String> metadata) {
        Map<String, String> mergedMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            mergedMetadata.putAll(metadata);
        }
        StoredDocumentReference reference = knowledgeObjectStorageService.store(title, content, source);
        if (reference.objectKey() != null) {
            mergedMetadata.put("objectKey", reference.objectKey());
        }
        if (reference.bucket() != null) {
            mergedMetadata.put("bucket", reference.bucket());
        }
        mergedMetadata.put("storageStatus", reference.stored() ? "stored" : "skipped");
        mergedMetadata.put("storageMessage", reference.message());

        KnowledgeDocument document = new KnowledgeDocument(
                UUID.randomUUID().toString(),
                title,
                content,
                source,
                mergedMetadata,
                Instant.now()
        );
        List<KnowledgeDocument> chunks = knowledgeChunker.chunk(document);
        chunks.forEach(knowledgeRepository::save);
        return document;
    }

    public RetrievalResult retrieve(String question, Map<String, String> filters) {
        String rewritten = queryRewriteService.rewrite(question);
        String route = ragRouter.route(rewritten);
        RetrievalRequest request = new RetrievalRequest(rewritten, filters, properties.getRag().getDefaultTopK());
        HybridRetrievalService.RetrievalTrace retrievalTrace = hybridRetrievalService.retrieveWithTrace(request);
        RerankService.RerankTrace rerankTrace = rerankService.rerank(rewritten, retrievalTrace.documents());

        ParentAggregation parentAggregation = aggregateParents(rerankTrace.documents());
        List<KnowledgeDocument> documents = parentAggregation.documents();

        List<String> reasons = new ArrayList<>(retrievalTrace.reasons());
        reasons.add(documents.isEmpty() ? "No document passed rerank stage" : "Reranked documents by token overlap and metadata match");
        if (parentAggregation.parentLookupCount() > 0) {
            reasons.add("Aggregated parent documents from child chunks");
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>(retrievalTrace.diagnostics());
        diagnostics.putAll(rerankTrace.diagnostics());
        diagnostics.put("filters", filters == null ? Map.of() : filters);
        diagnostics.put("route", route);
        diagnostics.put("resultCount", documents.size());
        diagnostics.put("parentAggregationApplied", parentAggregation.parentLookupCount() > 0);
        diagnostics.put("parentLookupCount", parentAggregation.parentLookupCount());
        diagnostics.put("childChunkCount", parentAggregation.childChunkCount());

        String summary = documents.isEmpty()
                ? "No matching knowledge found"
                : "Retrieved " + documents.size() + " documents via " + route;
        return new RetrievalResult(rewritten, documents, route, summary, reasons, diagnostics);
    }

    private ParentAggregation aggregateParents(List<KnowledgeDocument> reranked) {
        List<String> parentIds = reranked.stream()
                .map(KnowledgeDocument::metadata)
                .filter(metadata -> metadata != null && metadata.containsKey("parentDocumentId"))
                .map(metadata -> metadata.get("parentDocumentId"))
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
            String parentId = doc.metadata() == null ? null : doc.metadata().get("parentDocumentId");
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

        return new ParentAggregation(new ArrayList<>(new LinkedHashSet<>(merged.values())), parentIds.size(), childChunks);
    }

    private record ParentAggregation(
            List<KnowledgeDocument> documents,
            int parentLookupCount,
            int childChunkCount
    ) {
    }
}
