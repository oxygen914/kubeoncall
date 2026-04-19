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

        List<KnowledgeDocument> documents = rerankTrace.documents();
        List<String> reasons = new ArrayList<>(retrievalTrace.reasons());
        reasons.add(documents.isEmpty() ? "No document passed rerank stage" : "Reranked documents by token overlap and metadata match");

        Map<String, Object> diagnostics = new LinkedHashMap<>(retrievalTrace.diagnostics());
        diagnostics.putAll(rerankTrace.diagnostics());
        diagnostics.put("filters", filters == null ? Map.of() : filters);
        diagnostics.put("route", route);
        diagnostics.put("resultCount", documents.size());

        String summary = documents.isEmpty()
                ? "No matching knowledge found"
                : "Retrieved " + documents.size() + " documents via " + route;
        return new RetrievalResult(rewritten, documents, route, summary, reasons, diagnostics);
    }
}
