package com.kubeoncall.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.rag.repository.KnowledgeIndexAdmin;
import com.kubeoncall.rag.runbook.RunbookImportService;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.web.dto.KnowledgeIngestRequest;
import com.kubeoncall.web.dto.RunbookImportRequest;

class KnowledgeControllerTest {

    @Test
    void shouldExposeRunbookDryRunImport() {
        KnowledgeIngestService knowledgeIngestService = mock(KnowledgeIngestService.class);
        RunbookImportService runbookImportService = mock(RunbookImportService.class);
        RunbookImportService.ImportResult expected =
                new RunbookImportService.ImportResult(8, 8, 0, 0, 0, true, List.of());
        when(runbookImportService.importAll(true)).thenReturn(expected);
        KnowledgeController controller = new KnowledgeController(
                knowledgeIngestService,
                runbookImportService,
                mock(KnowledgeIndexAdmin.class),
                mock(ExecutionAuditService.class),
                mock(KubeOnCallMetricsService.class));

        RunbookImportService.ImportResult result = controller.importRunbooks(new RunbookImportRequest(true));

        assertTrue(result.dryRun());
        verify(runbookImportService).importAll(true);
    }

    @Test
    void shouldAuditAndMeasureKnowledgeIngest() {
        KnowledgeIngestService knowledgeIngestService = mock(KnowledgeIngestService.class);
        RunbookImportService runbookImportService = mock(RunbookImportService.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        KnowledgeDocument document =
                new KnowledgeDocument("doc-1", "CPU SOP", "check cpu", "manual", Map.of(), Instant.now());
        when(knowledgeIngestService.ingest("CPU SOP", "check cpu", "manual", Map.of()))
                .thenReturn(document);
        KnowledgeController controller = new KnowledgeController(
                knowledgeIngestService,
                runbookImportService,
                mock(KnowledgeIndexAdmin.class),
                auditService,
                metricsService);

        KnowledgeDocument result =
                controller.ingest(new KnowledgeIngestRequest("CPU SOP", "check cpu", "manual", Map.of()));

        assertTrue("doc-1".equals(result.id()));
        verify(metricsService).recordKnowledge("ingest", "SUCCESS", 1);
        verify(auditService)
                .recordKnowledgeOperation(eq("ingest"), eq("SUCCESS"), any(), any(Instant.class), any(Map.class));
    }
}
