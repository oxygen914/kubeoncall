package com.kubeoncall.memory;

import java.time.Instant;
import java.util.List;

/** Durable status for an asynchronous memory extraction task. */
public record MemoryExtractionStatus(
        String taskId,
        String status,
        int attempts,
        String extractionMode,
        int extractedCount,
        int discardedCount,
        List<String> memoryIds,
        String errorType,
        Instant updatedAt) {

    public MemoryExtractionStatus {
        memoryIds = memoryIds == null ? List.of() : List.copyOf(memoryIds);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static MemoryExtractionStatus pending(MemoryExtractionTask task) {
        return state(task, "PENDING", null, 0, 0, List.of(), null);
    }

    public static MemoryExtractionStatus processing(MemoryExtractionTask task) {
        return state(task, "PROCESSING", null, 0, 0, List.of(), null);
    }

    public static MemoryExtractionStatus completed(
            MemoryExtractionTask task,
            MemoryExtractionPipeline.ExtractionResult result,
            List<MemoryEntry> persistedEntries) {
        List<String> ids = persistedEntries == null
                ? List.of()
                : persistedEntries.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(MemoryEntry::id)
                        .filter(java.util.Objects::nonNull)
                        .toList();
        return state(
                task,
                "COMPLETED",
                result == null ? null : result.mode(),
                ids.size(),
                result == null ? 0 : result.discarded(),
                ids,
                null);
    }

    public static MemoryExtractionStatus retrying(MemoryExtractionTask task, String errorType) {
        return state(task, "RETRYING", null, 0, 0, List.of(), errorType);
    }

    public static MemoryExtractionStatus deadLetter(MemoryExtractionTask task, String errorType) {
        return state(task, "DEAD_LETTER", null, 0, 0, List.of(), errorType);
    }

    private static MemoryExtractionStatus state(
            MemoryExtractionTask task,
            String status,
            String mode,
            int extracted,
            int discarded,
            List<String> memoryIds,
            String errorType) {
        return new MemoryExtractionStatus(
                task.id(), status, task.attempts(), mode, extracted, discarded, memoryIds, errorType, Instant.now());
    }
}
