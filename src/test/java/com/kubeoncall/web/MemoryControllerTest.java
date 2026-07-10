package com.kubeoncall.web;

import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryConsolidationService;
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryService;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.web.dto.MemorySearchRequest;
import com.kubeoncall.web.dto.MemorySearchResponse;
import com.kubeoncall.web.dto.MemoryConsolidationRequest;
import com.kubeoncall.web.dto.MemoryConsolidationResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryControllerTest {

    @Test
    void shouldExposeHybridTraceOnlyWhenRequested() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryEntry entry = new MemoryEntry(
                "memory-1", MemoryType.SERVICE_FACT, MemoryScope.SERVICE,
                "payment owner", "team-payments", "payment-service", null, null,
                Instant.now(), Instant.now(), Map.of());
        when(memoryService.searchWithTrace("payment owner", Map.of("service", "payment-service"), 3))
                .thenReturn(new MemoryService.MemorySearchResult(
                        List.of(entry), Map.of("rankingSource", "reciprocal_rank_fusion")));
        MemoryController controller = new MemoryController(memoryService);

        MemorySearchResponse response = controller.search(new MemorySearchRequest(
                "payment owner", Map.of("service", "payment-service"), 3, true));

        assertEquals(List.of(entry), response.entries());
        assertEquals("reciprocal_rank_fusion", response.diagnostics().get("rankingSource"));
        verify(memoryService).searchWithTrace("payment owner", Map.of("service", "payment-service"), 3);
    }

    @Test
    void shouldExposeConsolidationDryRun() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        when(consolidationService.consolidate(any(Instant.class), org.mockito.ArgumentMatchers.eq(40), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new MemoryConsolidationService.ConsolidationResult(
                        10, 2, 3, 0, 4, 0, "dry_run", 40, 0.92d, true));
        MemoryController controller = new MemoryController(memoryService, consolidationService);

        MemoryConsolidationResponse response = controller.consolidate(
                new MemoryConsolidationRequest(40, true));

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
                .thenReturn(new MemoryService.MemoryRestoreResult(
                        "memory-1", "success", restoredAt, "stale_cleanup"));
        MemoryController controller = new MemoryController(memoryService);

        com.kubeoncall.web.dto.MemoryRestoreResponse response = controller.restore("memory-1");

        assertEquals("success", response.status());
        assertEquals("stale_cleanup", response.previousDeleteReason());
    }
}
