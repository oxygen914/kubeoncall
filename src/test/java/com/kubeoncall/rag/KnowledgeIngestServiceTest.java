package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestServiceTest {

    @Test
    void shouldIngestChunksAndStoreMetadata() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        QueryRewriteService rewriteService = new QueryRewriteService();
        RagRouter ragRouter = new RagRouter();
        HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        RerankService rerankService = new RerankService(properties);
        KnowledgeChunker chunker = new KnowledgeChunker();
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);

        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference("knowledge/doc.txt", "bucket-a", true, "ok"));

        KnowledgeIngestService service = new KnowledgeIngestService(
                repository,
                rewriteService,
                ragRouter,
                retrievalService,
                rerankService,
                properties,
                chunker,
                storageService
        );

        String content = "x".repeat(600);
        KnowledgeDocument document = service.ingest("title", content, "manual", Map.of("env", "lab"));

        assertEquals("stored", document.metadata().get("storageStatus"));
        assertEquals("knowledge/doc.txt", document.metadata().get("objectKey"));
        verify(repository, times(3)).save(any(KnowledgeDocument.class));
    }

    @Test
    void shouldRetrieveWithRouteAndDiagnostics() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        QueryRewriteService rewriteService = new QueryRewriteService();
        RagRouter ragRouter = new RagRouter();
        HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
        RerankService rerankService = mock(RerankService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setDefaultTopK(2);
        KnowledgeChunker chunker = new KnowledgeChunker();
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);

        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference(null, null, false, "skip"));

        KnowledgeDocument parent = new KnowledgeDocument(
                "parent-1",
                "payment timeout",
                "runbook parent",
                "manual",
                Map.of("env", "lab"),
                Instant.now()
        );
        KnowledgeDocument child = new KnowledgeDocument(
                "parent-1#chunk-1",
                "payment timeout",
                "runbook chunk",
                "manual",
                Map.of("env", "lab", "parentDocumentId", "parent-1"),
                Instant.now()
        );
        when(retrievalService.retrieveWithTrace(any(RetrievalRequest.class)))
                .thenReturn(new HybridRetrievalService.RetrievalTrace(
                        List.of(child),
                        List.of("Applied lexical retrieval over title/content"),
                        Map.of("candidateCount", 1, "latencyMs", 2)
                ));
        when(rerankService.rerank(anyString(), any()))
                .thenReturn(new RerankService.RerankTrace(
                        List.of(child),
                        Map.of("rerankLatencyMs", 1, "scoreByDocument", Map.of("parent-1#chunk-1", 8))
                ));
        when(repository.loadParents(anyList())).thenReturn(Map.of("parent-1", parent));

        KnowledgeIngestService service = new KnowledgeIngestService(
                repository,
                rewriteService,
                ragRouter,
                retrievalService,
                rerankService,
                properties,
                chunker,
                storageService
        );

        RetrievalResult result = service.retrieve("payment timeout 怎么处理", Map.of("env", "lab"));

        assertEquals("RAG", result.route());
        assertEquals(1, result.documents().size());
        assertEquals("parent-1", result.documents().get(0).id());
        assertEquals(1, result.diagnostics().get("resultCount"));
        assertEquals(true, result.diagnostics().get("parentAggregationApplied"));
        assertTrue(result.retrievalReasons().stream().anyMatch(reason -> reason.contains("Aggregated parent")));
        verify(retrievalService).retrieveWithTrace(any(RetrievalRequest.class));
        verify(rerankService).rerank(anyString(), any());
        verify(repository).loadParents(anyList());
        verify(repository, never()).search(any());
    }
}
