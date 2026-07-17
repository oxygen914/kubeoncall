package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;

class KnowledgeIngestServiceTest {

    @Test
    void shouldGenerateAndPersistEmbeddingForChunks() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setMockEmbeddingEnabled(true);
        properties.getRag().setEmbeddingDimensions(6);
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);
        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference(null, null, false, "skip"));
        KnowledgeIngestionFacade facade = new KnowledgeIngestionFacade(
                repository,
                new KnowledgeChunker(properties),
                storageService,
                new EmbeddingService(List.of(), properties),
                properties);

        facade.ingest("CPU runbook", "check cpu usage", "manual", Map.of());

        ArgumentCaptor<KnowledgeDocument> documentCaptor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository, times(2)).save(documentCaptor.capture());
        KnowledgeDocument chunk = documentCaptor.getAllValues().get(1);
        assertEquals("ready", chunk.metadata().get("embedding_status"));
        assertEquals("deterministic_mock", chunk.metadata().get("embedding_provider"));
        assertEquals("check cpu usage", chunk.embeddingText());
        assertEquals(6, chunk.embedding().size());
    }

    @Test
    void shouldIngestChunksAndStoreMetadata() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        QueryRewriteService rewriteService = new QueryRewriteService();
        RagRouter ragRouter = new RagRouter();
        HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        RerankService rerankService = new RerankService(properties, List.of(), mock(KubeOnCallMetricsService.class));
        KnowledgeChunker chunker = new KnowledgeChunker(properties);
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);

        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference("knowledge/doc.txt", "bucket-a", true, "ok"));

        KnowledgeIngestService service = service(
                repository,
                rewriteService,
                ragRouter,
                retrievalService,
                rerankService,
                properties,
                chunker,
                storageService);

        String content = "x".repeat(600);
        KnowledgeDocument document = service.ingest("title", content, "manual", Map.of("env", "lab"));

        assertEquals("stored", document.metadata().get("storageStatus"));
        assertEquals("knowledge/doc.txt", document.metadata().get("objectKey"));
        assertEquals("false", document.metadata().get("chunk_enable"));
        assertEquals(document.id(), document.metadata().get("doc_id"));
        assertTrue(document.metadata().containsKey("created_at"));
        assertTrue(document.metadata().containsKey("updated_at"));
        verify(repository, times(4)).save(any(KnowledgeDocument.class));
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
        KnowledgeChunker chunker = new KnowledgeChunker(properties);
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);

        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference(null, null, false, "skip"));

        KnowledgeDocument parent = new KnowledgeDocument(
                "parent-1", "payment timeout", "runbook parent", "manual", Map.of("env", "lab"), Instant.now());
        KnowledgeDocument child = new KnowledgeDocument(
                "parent-1#chunk-1",
                "payment timeout",
                "runbook chunk",
                "manual",
                Map.of("env", "lab", "parentDocumentId", "parent-1"),
                Instant.now());
        when(retrievalService.retrieveWithTrace(any(RetrievalRequest.class)))
                .thenReturn(new HybridRetrievalService.RetrievalTrace(
                        List.of(child),
                        List.of("Applied lexical retrieval over title/content"),
                        Map.of("candidateCount", 1, "latencyMs", 2)));
        when(rerankService.rerank(anyString(), any()))
                .thenReturn(new RerankService.RerankTrace(
                        List.of(child),
                        Map.of("rerankLatencyMs", 1, "scoreByDocument", Map.of("parent-1#chunk-1", 8))));
        when(repository.loadParents(anyList())).thenReturn(Map.of("parent-1", parent));

        KnowledgeIngestService service = service(
                repository,
                rewriteService,
                ragRouter,
                retrievalService,
                rerankService,
                properties,
                chunker,
                storageService);

        RetrievalResult result = service.retrieve("payment timeout 怎么处理", Map.of("env", "lab"));

        assertEquals("RAG", result.route());
        assertEquals(1, result.documents().size());
        assertEquals("parent-1", result.documents().get(0).id());
        assertEquals(1, result.diagnostics().get("resultCount"));
        assertEquals(true, result.diagnostics().get("parentAggregationApplied"));
        assertEquals("payment timeout 怎么处理", result.diagnostics().get("rawQuery"));
        assertEquals(result.rewrittenQuery(), result.diagnostics().get("rewrittenQuery"));
        assertTrue(result.retrievalReasons().stream().anyMatch(reason -> reason.contains("Aggregated parent")));
        verify(retrievalService).retrieveWithTrace(any(RetrievalRequest.class));
        verify(rerankService).rerank(anyString(), any());
        verify(repository).loadParents(anyList());
        verify(repository, never()).search(any());
    }

    @Test
    void shouldExcludeMemoryDocumentsFromDefaultRetrieval() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        QueryRewriteService rewriteService = new QueryRewriteService();
        RagRouter ragRouter = new RagRouter();
        HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
        RerankService rerankService = mock(RerankService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        KnowledgeChunker chunker = new KnowledgeChunker(properties);
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);

        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference(null, null, false, "skip"));

        KnowledgeDocument sop = new KnowledgeDocument(
                "sop-1", "payment sop", "restart runbook", "manual", Map.of("source_type", "sop"), Instant.now());
        KnowledgeDocument memory = new KnowledgeDocument(
                "memory-1",
                "payment memory",
                "last incident note",
                "memory",
                Map.of("source_type", "memory"),
                Instant.now());
        when(retrievalService.retrieveWithTrace(any(RetrievalRequest.class)))
                .thenReturn(new HybridRetrievalService.RetrievalTrace(
                        List.of(memory, sop),
                        List.of("Applied lexical retrieval over title/content"),
                        Map.of("candidateCount", 2)));
        when(rerankService.rerank(anyString(), any()))
                .thenReturn(new RerankService.RerankTrace(List.of(sop), Map.of("scoreByDocument", Map.of("sop-1", 1))));

        KnowledgeIngestService service = service(
                repository,
                rewriteService,
                ragRouter,
                retrievalService,
                rerankService,
                properties,
                chunker,
                storageService);

        RetrievalResult result = service.retrieve("payment 怎么处理", Map.of());

        assertEquals(
                List.of("sop-1"),
                result.documents().stream().map(KnowledgeDocument::id).toList());
        assertEquals(1, result.diagnostics().get("memoryDocumentsExcluded"));
        assertEquals(false, result.diagnostics().get("memorySearchExplicit"));
        assertTrue(result.retrievalReasons().stream().anyMatch(reason -> reason.contains("Excluded long-term memory")));
    }

    @Test
    void shouldUpsertByDocumentIdAndSoftDeleteThenRestoreChunks() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setChunkSize(32);
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);
        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference(null, null, false, "skip"));
        KnowledgeIngestionFacade facade = new KnowledgeIngestionFacade(
                repository,
                new KnowledgeChunker(properties),
                storageService,
                new EmbeddingService(List.of(), properties),
                properties);

        facade.ingest("CPU runbook", "x".repeat(100), "manual", Map.of("doc_id", "cpu-runbook"));
        int firstChunkCount = repository.findByMetadata("doc_id", "cpu-runbook").size();
        String createdAt = repository.findByMetadata("doc_id", "cpu-runbook").stream()
                .filter(document -> document.id().equals("cpu-runbook"))
                .findFirst()
                .orElseThrow()
                .metadata()
                .get("created_at");
        facade.ingest("CPU runbook", "short content", "manual", Map.of("doc_id", "cpu-runbook"));

        assertEquals(2, repository.findByMetadata("doc_id", "cpu-runbook").size());
        assertTrue(firstChunkCount > 2);
        assertEquals(
                createdAt,
                repository.findByMetadata("doc_id", "cpu-runbook").stream()
                        .filter(document -> document.id().equals("cpu-runbook"))
                        .findFirst()
                        .orElseThrow()
                        .metadata()
                        .get("created_at"));
        KnowledgeIngestionFacade.LifecycleResult deleted = facade.softDelete("cpu-runbook", "expired");
        assertEquals(2, deleted.affected());
        assertTrue(repository.findByMetadata("doc_id", "cpu-runbook").stream()
                .allMatch(document -> "false".equals(document.metadata().get("chunk_enable"))));

        KnowledgeIngestionFacade.LifecycleResult restored = facade.restore("cpu-runbook");

        assertTrue(restored.found());
        assertEquals(
                List.of("false", "true"),
                repository.findByMetadata("doc_id", "cpu-runbook").stream()
                        .map(document -> document.metadata().get("chunk_enable"))
                        .sorted()
                        .toList());
    }

    @Test
    void shouldReturnExistingDocumentForRepeatedFileHashWithoutDocumentId() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KubeOnCallProperties properties = new KubeOnCallProperties();
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);
        when(storageService.store(anyString(), anyString(), anyString()))
                .thenReturn(new StoredDocumentReference(null, null, false, "skip"));
        KnowledgeIngestionFacade facade = new KnowledgeIngestionFacade(
                repository,
                new KnowledgeChunker(properties),
                storageService,
                new EmbeddingService(List.of(), properties),
                properties);

        KnowledgeIngestionFacade.IngestionResult first =
                facade.ingestWithResult("CPU runbook", "same content", "manual", Map.of());
        KnowledgeIngestionFacade.IngestionResult repeated =
                facade.ingestWithResult("CPU runbook", "same content", "manual", Map.of());

        assertEquals("created", first.operation());
        assertEquals("duplicate", repeated.operation());
        assertEquals(first.document().id(), repeated.document().id());
        verify(storageService, times(1)).store("CPU runbook", "same content", "manual");
    }

    @Test
    void shouldCompensateRepositoryAndObjectStorageWhenChunkPersistenceFails() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setChunkSize(32);
        KnowledgeObjectStorageService storageService = mock(KnowledgeObjectStorageService.class);
        StoredDocumentReference reference = new StoredDocumentReference("knowledge/doc.txt", "bucket-a", true, "ok");
        when(storageService.store(anyString(), anyString(), anyString())).thenReturn(reference);
        doThrow(new IllegalStateException("repository unavailable"))
                .when(repository)
                .save(org.mockito.ArgumentMatchers.argThat(
                        (KnowledgeDocument document) -> document.id().contains("#chunk-")));
        KnowledgeIngestionFacade facade = new KnowledgeIngestionFacade(
                repository,
                new KnowledgeChunker(properties),
                storageService,
                new EmbeddingService(List.of(), properties),
                properties);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> facade.ingest("CPU runbook", "x".repeat(100), "manual", Map.of()));

        verify(repository, org.mockito.Mockito.atLeast(2)).deleteById(anyString());
        verify(storageService).remove(reference);
    }

    private static KnowledgeIngestService service(
            KnowledgeRepository repository,
            QueryRewriteService rewriteService,
            RagRouter ragRouter,
            HybridRetrievalService retrievalService,
            RerankService rerankService,
            KubeOnCallProperties properties,
            KnowledgeChunker chunker,
            KnowledgeObjectStorageService storageService) {
        KnowledgeIngestionFacade ingestionFacade = new KnowledgeIngestionFacade(
                repository, chunker, storageService, new EmbeddingService(List.of(), properties), properties);
        KnowledgeRetrievalFacade retrievalFacade = new KnowledgeRetrievalFacade(
                repository, rewriteService, ragRouter, retrievalService, rerankService, properties);
        return new KnowledgeIngestService(ingestionFacade, retrievalFacade);
    }

    private static final class InMemoryKnowledgeRepository implements KnowledgeRepository {

        private final Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();

        @Override
        public void save(KnowledgeDocument document) {
            documents.put(document.id(), document);
        }

        @Override
        public List<KnowledgeDocument> searchLexical(RetrievalRequest request, int candidateSize) {
            return List.of();
        }

        @Override
        public List<KnowledgeDocument> searchVector(RetrievalRequest request, int candidateSize) {
            return List.of();
        }

        @Override
        public Map<String, KnowledgeDocument> loadParents(List<String> parentDocumentIds) {
            return Map.of();
        }

        @Override
        public List<KnowledgeDocument> findByMetadata(String key, String value) {
            return documents.values().stream()
                    .filter(document -> value.equals(document.metadata().get(key)))
                    .toList();
        }

        @Override
        public void deleteById(String documentId) {
            documents.remove(documentId);
        }
    }
}
