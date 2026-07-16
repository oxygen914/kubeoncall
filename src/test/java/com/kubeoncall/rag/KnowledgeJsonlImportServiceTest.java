package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.domain.rag.KnowledgeDocument;

class KnowledgeJsonlImportServiceTest {

    @Test
    void shouldImportValidLinesAndContinueAfterInvalidLine() {
        KnowledgeIngestService knowledgeIngestService = mock(KnowledgeIngestService.class);
        when(knowledgeIngestService.ingest(eq("CPU runbook"), eq("check cpu"), eq("jsonl"), any()))
                .thenReturn(new KnowledgeDocument(
                        "cpu-runbook", "CPU runbook", "check cpu", "jsonl", Map.of(), Instant.now()));
        KnowledgeJsonlImportService service =
                new KnowledgeJsonlImportService(new ObjectMapper(), knowledgeIngestService);

        KnowledgeJsonlImportService.ImportResult result = service.importJsonl(
                "{\"id\":\"cpu-runbook\",\"title\":\"CPU runbook\",\"content\":\"check cpu\",\"metadata\":{\"env\":\"prod\"}}\n"
                        + "{\"title\":\"missing content\"}");

        assertEquals(2, result.scanned());
        assertEquals(1, result.imported());
        assertEquals(1, result.failed());
        assertEquals("imported", result.lines().get(0).status());
        assertEquals("failed", result.lines().get(1).status());
        verify(knowledgeIngestService)
                .ingest("CPU runbook", "check cpu", "jsonl", Map.of("doc_id", "cpu-runbook", "env", "prod"));
    }
}
