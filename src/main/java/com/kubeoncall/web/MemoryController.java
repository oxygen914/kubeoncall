package com.kubeoncall.web;

import com.kubeoncall.memory.MemoryService;
import com.kubeoncall.memory.MemoryConsolidationService;
import com.kubeoncall.web.dto.MemoryCleanupRequest;
import com.kubeoncall.web.dto.MemoryCleanupResponse;
import com.kubeoncall.web.dto.MemorySearchRequest;
import com.kubeoncall.web.dto.MemorySearchResponse;
import com.kubeoncall.web.dto.MemoryConsolidationRequest;
import com.kubeoncall.web.dto.MemoryConsolidationResponse;
import com.kubeoncall.web.dto.MemoryRestoreResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final int DEFAULT_SCAN_LIMIT = 500;
    private static final int DEFAULT_SEARCH_TOP_K = 5;

    private final MemoryService memoryService;
    private final MemoryConsolidationService consolidationService;

    @Autowired
    public MemoryController(MemoryService memoryService,
                            MemoryConsolidationService consolidationService) {
        this.memoryService = memoryService;
        this.consolidationService = consolidationService;
    }

    public MemoryController(MemoryService memoryService) {
        this(memoryService, null);
    }

    @PostMapping("/cleanup")
    public MemoryCleanupResponse cleanup(@RequestBody(required = false) MemoryCleanupRequest request) {
        int scanLimit = request == null || request.scanLimit() == null ? DEFAULT_SCAN_LIMIT : request.scanLimit();
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        MemoryService.MemoryCleanupResult result = memoryService.cleanupStale(Instant.now(), scanLimit, dryRun);
        return new MemoryCleanupResponse(
                result.scanned(),
                result.eligible(),
                result.deleted(),
                result.status(),
                result.scanLimit(),
                result.staleThreshold(),
                result.staleAfterDays(),
                result.dryRun());
    }

    @PostMapping("/search")
    public MemorySearchResponse search(@RequestBody MemorySearchRequest request) {
        int topK = request.topK() == null || request.topK() <= 0
                ? DEFAULT_SEARCH_TOP_K
                : request.topK();
        MemoryService.MemorySearchResult result = memoryService.searchWithTrace(
                request.query(), request.filters(), topK);
        Map<String, Object> diagnostics = Boolean.TRUE.equals(request.includeTrace())
                ? result.diagnostics()
                : Map.of("memoryResultCount", result.entries().size());
        return new MemorySearchResponse(result.entries(), diagnostics);
    }

    @PostMapping("/consolidate")
    public MemoryConsolidationResponse consolidate(
            @RequestBody(required = false) MemoryConsolidationRequest request) {
        if (consolidationService == null) {
            throw new IllegalStateException("Memory consolidation service is unavailable");
        }
        int scanLimit = request == null || request.scanLimit() == null
                ? DEFAULT_SCAN_LIMIT
                : request.scanLimit();
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        MemoryConsolidationService.ConsolidationResult result = consolidationService.consolidate(
                Instant.now(), scanLimit, dryRun);
        return new MemoryConsolidationResponse(
                result.scanned(), result.duplicateGroups(), result.eligible(), result.consolidated(),
                result.normalizationEligible(), result.normalized(),
                result.status(), result.scanLimit(), result.similarityThreshold(), result.dryRun());
    }

    @PostMapping("/{memoryId}/restore")
    public MemoryRestoreResponse restore(@PathVariable String memoryId) {
        MemoryService.MemoryRestoreResult result = memoryService.restore(memoryId, Instant.now());
        return new MemoryRestoreResponse(
                result.memoryId(), result.status(), result.restoredAt(), result.previousDeleteReason());
    }
}
