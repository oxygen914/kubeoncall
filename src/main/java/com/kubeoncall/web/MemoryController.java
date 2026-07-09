package com.kubeoncall.web;

import com.kubeoncall.memory.MemoryService;
import com.kubeoncall.web.dto.MemoryCleanupRequest;
import com.kubeoncall.web.dto.MemoryCleanupResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final int DEFAULT_SCAN_LIMIT = 500;

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostMapping("/cleanup")
    public MemoryCleanupResponse cleanup(@RequestBody(required = false) MemoryCleanupRequest request) {
        int scanLimit = request == null || request.scanLimit() == null ? DEFAULT_SCAN_LIMIT : request.scanLimit();
        MemoryService.MemoryCleanupResult result = memoryService.cleanupStale(Instant.now(), scanLimit);
        return new MemoryCleanupResponse(result.scanned(), result.deleted(), result.status());
    }
}
