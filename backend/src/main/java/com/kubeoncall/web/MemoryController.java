package com.kubeoncall.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kubeoncall.memory.MemoryConsolidationService;
import com.kubeoncall.memory.MemoryExtractionQueue;
import com.kubeoncall.memory.MemoryExtractionStatus;
import com.kubeoncall.memory.MemoryExtractionTask;
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryService;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.web.dto.MemoryCleanupRequest;
import com.kubeoncall.web.dto.MemoryCleanupResponse;
import com.kubeoncall.web.dto.MemoryConsolidationRequest;
import com.kubeoncall.web.dto.MemoryConsolidationResponse;
import com.kubeoncall.web.dto.MemoryExtractionReplayRequest;
import com.kubeoncall.web.dto.MemoryExtractionReplayResponse;
import com.kubeoncall.web.dto.MemoryExtractionRequest;
import com.kubeoncall.web.dto.MemoryExtractionSubmissionResponse;
import com.kubeoncall.web.dto.MemoryRestoreResponse;
import com.kubeoncall.web.dto.MemorySearchRequest;
import com.kubeoncall.web.dto.MemorySearchResponse;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final int DEFAULT_SCAN_LIMIT = 500;
    private static final int DEFAULT_SEARCH_TOP_K = 5;

    private final MemoryService memoryService;
    private final MemoryConsolidationService consolidationService;
    private final MemoryExtractionQueue extractionQueue;

    public MemoryController(
            MemoryService memoryService,
            MemoryConsolidationService consolidationService,
            MemoryExtractionQueue extractionQueue) {
        this.memoryService = memoryService;
        this.consolidationService = consolidationService;
        this.extractionQueue = extractionQueue;
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
        int topK = request.topK() == null || request.topK() <= 0 ? DEFAULT_SEARCH_TOP_K : request.topK();
        MemoryService.MemorySearchResult result =
                memoryService.searchWithTrace(request.query(), request.filters(), topK);
        Map<String, Object> diagnostics = Boolean.TRUE.equals(request.includeTrace())
                ? result.diagnostics()
                : Map.of("memoryResultCount", result.entries().size());
        return new MemorySearchResponse(result.entries(), diagnostics);
    }

    @PostMapping("/consolidate")
    public MemoryConsolidationResponse consolidate(@RequestBody(required = false) MemoryConsolidationRequest request) {
        int scanLimit = request == null || request.scanLimit() == null ? DEFAULT_SCAN_LIMIT : request.scanLimit();
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        MemoryConsolidationService.ConsolidationResult result =
                consolidationService.consolidate(Instant.now(), scanLimit, dryRun);
        return new MemoryConsolidationResponse(
                result.scanned(),
                result.duplicateGroups(),
                result.eligible(),
                result.consolidated(),
                result.normalizationEligible(),
                result.normalized(),
                result.status(),
                result.scanLimit(),
                result.similarityThreshold(),
                result.dryRun());
    }

    @PostMapping("/{memoryId}/restore")
    public MemoryRestoreResponse restore(@PathVariable String memoryId) {
        MemoryService.MemoryRestoreResult result = memoryService.restore(memoryId, Instant.now());
        return new MemoryRestoreResponse(
                result.memoryId(), result.status(), result.restoredAt(), result.previousDeleteReason());
    }

    @PostMapping("/extractions/dead-letter/replay")
    public MemoryExtractionReplayResponse replayExtractions(
            @RequestBody(required = false) MemoryExtractionReplayRequest request) {
        int limit = request == null || request.limit() == null ? 100 : request.limit();
        return new MemoryExtractionReplayResponse(extractionQueue.replayDeadLetters(limit));
    }

    @PostMapping("/extractions")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MemoryExtractionSubmissionResponse submitExtraction(@Valid @RequestBody MemoryExtractionRequest request) {
        MemoryType type = request.memoryType() == null ? MemoryType.SERVICE_FACT : request.memoryType();
        MemoryScope scope = request.scope() == null ? inferScope(request) : request.scope();
        Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        metadata.putIfAbsent("source", "manual_api");
        MemoryExtractionTask task = MemoryExtractionTask.create(
                type,
                scope,
                request.subject(),
                request.content(),
                request.service(),
                request.resource(),
                request.fingerprint(),
                metadata);
        extractionQueue.enqueue(task);
        return new MemoryExtractionSubmissionResponse(task.id(), "PENDING");
    }

    @GetMapping("/extractions/{taskId}")
    public MemoryExtractionStatus extractionStatus(@PathVariable String taskId) {
        return extractionQueue
                .status(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "extraction task not found"));
    }

    private MemoryScope inferScope(MemoryExtractionRequest request) {
        if (request.fingerprint() != null && !request.fingerprint().isBlank()) {
            return MemoryScope.FINGERPRINT;
        }
        if (request.resource() != null && !request.resource().isBlank()) {
            return MemoryScope.RESOURCE;
        }
        if (request.service() != null && !request.service().isBlank()) {
            return MemoryScope.SERVICE;
        }
        return MemoryScope.GLOBAL;
    }
}
