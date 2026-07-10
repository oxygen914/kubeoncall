package com.kubeoncall.rag.runbook;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunbookImportServiceTest {

    private static final RunbookAsset ASSET = new RunbookAsset(
            "runbook-pod-oom",
            "Pod OOM",
            "production procedure",
            "runbook-pod-oom.md",
            Map.of("runbookId", "runbook-pod-oom", "dataset_version", "v1", "category", "k8s-pod"));

    @Test
    void shouldReportDryRunWithoutWriting() {
        RunbookCatalog catalog = mock(RunbookCatalog.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(catalog.load()).thenReturn(List.of(ASSET));
        when(repository.searchLexical(any(RetrievalRequest.class), anyInt())).thenReturn(List.of());

        RunbookImportService.ImportResult result =
                new RunbookImportService(catalog, ingestService, repository).importAll(true);

        assertEquals(1, result.eligible());
        assertEquals(0, result.imported());
        assertEquals("eligible", result.assets().get(0).status());
        verify(ingestService, never()).ingest(any(), any(), any(), any());
    }

    @Test
    void shouldSkipAlreadyImportedVersionUsingExactMetadataFilters() {
        RunbookCatalog catalog = mock(RunbookCatalog.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(catalog.load()).thenReturn(List.of(ASSET));
        when(repository.searchLexical(any(RetrievalRequest.class), anyInt())).thenReturn(List.of(
                new KnowledgeDocument("chunk", "Pod OOM", "body", "runbook", Map.of(), Instant.now())));

        RunbookImportService.ImportResult result =
                new RunbookImportService(catalog, ingestService, repository).importAll(false);

        assertEquals(1, result.skipped());
        ArgumentCaptor<RetrievalRequest> request = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(repository).searchLexical(request.capture(), anyInt());
        assertEquals(Map.of(
                "runbookId", "runbook-pod-oom",
                "dataset_version", "v1",
                "chunk_enable", "true"), request.getValue().filters());
        verify(ingestService, never()).ingest(any(), any(), any(), any());
    }

    @Test
    void shouldImportEligibleAssetWithProductionMetadata() {
        RunbookCatalog catalog = mock(RunbookCatalog.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(catalog.load()).thenReturn(List.of(ASSET));
        when(repository.searchLexical(any(RetrievalRequest.class), anyInt())).thenReturn(List.of());

        RunbookImportService.ImportResult result =
                new RunbookImportService(catalog, ingestService, repository).importAll(false);

        assertEquals(1, result.imported());
        assertEquals(0, result.failed());
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(ingestService).ingest(
                org.mockito.ArgumentMatchers.eq("Pod OOM"),
                org.mockito.ArgumentMatchers.eq("production procedure"),
                org.mockito.ArgumentMatchers.eq("runbook"),
                metadata.capture());
        assertTrue(metadata.getValue().containsKey("runbookId"));
        assertEquals("k8s-pod", metadata.getValue().get("category"));
    }
}
