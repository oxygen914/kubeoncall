package com.kubeoncall.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kubeoncall.memory.MemoryConsolidationService;
import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryExtractionQueue;
import com.kubeoncall.memory.MemoryExtractionStatus;
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryService;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.web.dto.MemoryConsolidationRequest;
import com.kubeoncall.web.dto.MemoryConsolidationResponse;
import com.kubeoncall.web.dto.MemoryExtractionRequest;
import com.kubeoncall.web.dto.MemoryExtractionSubmissionResponse;
import com.kubeoncall.web.dto.MemorySearchRequest;
import com.kubeoncall.web.dto.MemorySearchResponse;

class MemoryControllerTest {

    @Test
    void shouldExposeHybridTraceOnlyWhenRequested() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryEntry entry = new MemoryEntry(
                "memory-1",
                MemoryType.SERVICE_FACT,
                MemoryScope.SERVICE,
                "payment owner",
                "team-payments",
                "payment-service",
                null,
                null,
                Instant.now(),
                Instant.now(),
                Map.of());
        when(memoryService.searchWithTrace("payment owner", Map.of("service", "payment-service"), 3))
                .thenReturn(new MemoryService.MemorySearchResult(
                        List.of(entry), Map.of("rankingSource", "reciprocal_rank_fusion")));
        MemoryController controller = controller(memoryService, mock(MemoryConsolidationService.class));

        MemorySearchResponse response = controller.search(
                new MemorySearchRequest("payment owner", Map.of("service", "payment-service"), 3, true));

        assertEquals(List.of(entry), response.entries());
        assertEquals("reciprocal_rank_fusion", response.diagnostics().get("rankingSource"));
        verify(memoryService).searchWithTrace("payment owner", Map.of("service", "payment-service"), 3);
    }

    @Test
    void shouldExposeConsolidationDryRun() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        when(consolidationService.consolidate(
                        any(Instant.class), org.mockito.ArgumentMatchers.eq(40), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new MemoryConsolidationService.ConsolidationResult(
                        10, 2, 3, 0, 4, 0, "dry_run", 40, 0.92d, true));
        MemoryController controller = controller(memoryService, consolidationService);

        MemoryConsolidationResponse response = controller.consolidate(new MemoryConsolidationRequest(40, true));

        assertEquals(3, response.eligible());
        assertEquals(0, response.consolidated());
        assertEquals(4, response.normalizationEligible());
        assertEquals(0, response.normalized());
        assertEquals(true, response.dryRun());
    }

    @Test
    void shouldRestoreMemoryById() {
        MemoryService memoryService = mock(MemoryService.class);
        Instant restoredAt = Instant.parse("2026-07-10T12:00:00Z");
        when(memoryService.restore(org.mockito.ArgumentMatchers.eq("memory-1"), any(Instant.class)))
                .thenReturn(new MemoryService.MemoryRestoreResult("memory-1", "success", restoredAt, "stale_cleanup"));
        MemoryController controller = controller(memoryService, mock(MemoryConsolidationService.class));

        com.kubeoncall.web.dto.MemoryRestoreResponse response = controller.restore("memory-1");

        assertEquals("success", response.status());
        assertEquals("stale_cleanup", response.previousDeleteReason());
    }

    @Test
    void shouldSubmitAndReadExtractionStatus() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        when(queue.status(any()))
                .thenAnswer(invocation -> Optional.of(new MemoryExtractionStatus(
                        invocation.getArgument(0),
                        "COMPLETED",
                        0,
                        "llm_structured",
                        1,
                        0,
                        List.of("memory-1"),
                        null,
                        Instant.now())));
        MemoryController controller =
                new MemoryController(mock(MemoryService.class), mock(MemoryConsolidationService.class), queue);

        MemoryExtractionSubmissionResponse submission = controller.submitExtraction(new MemoryExtractionRequest(
                MemoryType.SERVICE_FACT,
                null,
                "payment owner",
                "payment-service is owned by the payments platform team",
                "payment-service",
                null,
                null,
                Map.of("ticket", "OPS-42")));
        MemoryExtractionStatus status = controller.extractionStatus(submission.taskId());

        assertEquals("PENDING", submission.status());
        assertEquals("COMPLETED", status.status());
        verify(queue).enqueue(any());
    }

    private static MemoryController controller(
            MemoryService memoryService, MemoryConsolidationService consolidationService) {
        return new MemoryController(memoryService, consolidationService, mock(MemoryExtractionQueue.class));
    }
}
