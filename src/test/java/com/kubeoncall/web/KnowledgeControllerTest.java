package com.kubeoncall.web;

import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.rag.runbook.RunbookImportService;
import com.kubeoncall.web.dto.RunbookImportRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeControllerTest {

    @Test
    void shouldExposeRunbookDryRunImport() {
        KnowledgeIngestService knowledgeIngestService = mock(KnowledgeIngestService.class);
        RunbookImportService runbookImportService = mock(RunbookImportService.class);
        RunbookImportService.ImportResult expected = new RunbookImportService.ImportResult(
                8, 8, 0, 0, 0, true, List.of());
        when(runbookImportService.importAll(true)).thenReturn(expected);
        KnowledgeController controller = new KnowledgeController(knowledgeIngestService, runbookImportService);

        RunbookImportService.ImportResult result =
                controller.importRunbooks(new RunbookImportRequest(true));

        assertTrue(result.dryRun());
        verify(runbookImportService).importAll(true);
    }
}
