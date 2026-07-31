package com.kubeoncall.memory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.memory.mysql.MemoryEntryRecord;
import com.kubeoncall.memory.mysql.MemoryEntryRepository;
import com.kubeoncall.memory.mysql.MemoryEntryRepository.UpsertMemory;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRecord;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;

/** Executes one durable structured-memory extraction and persists its governance facts. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MemoryExtractionTaskHandler implements AsyncTaskHandler {

    private final MemoryExtractionPipeline extractionPipeline;
    private final MemoryService memoryService;
    private final MemoryEntryRepository memoryRepository;
    private final MemoryExtractionTaskRepository extractionRepository;
    private final AsyncTaskRepository taskRepository;

    public MemoryExtractionTaskHandler(
            MemoryExtractionPipeline extractionPipeline,
            MemoryService memoryService,
            MemoryEntryRepository memoryRepository,
            MemoryExtractionTaskRepository extractionRepository,
            AsyncTaskRepository taskRepository) {
        this.extractionPipeline = extractionPipeline;
        this.memoryService = memoryService;
        this.memoryRepository = memoryRepository;
        this.extractionRepository = extractionRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    public String taskType() {
        return MemoryGovernanceService.EXTRACTION_TASK_TYPE;
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        Map<String, Object> request = context.task().request();
        MemoryExtractionTaskRecord extraction = requireExtraction(context.task().resourcePublicId());
        updateTaskProgress(context, "extracting", 10);
        if (!extractionRepository.updateProgress(extraction.publicId(), extraction.version(), 1, 0, Instant.now())) {
            throw new IllegalStateException("Memory extraction state changed before processing");
        }

        MemoryExtractionTask task = toExtractionTask(extraction, request);
        MemoryExtractionPipeline.ExtractionResult result = extractionPipeline.extract(task);
        updateTaskProgress(context, "persisting", 65);

        List<MemoryEntryRecord> persisted = new ArrayList<>();
        for (MemoryEntry candidate : result.entries()) {
            context.requireValidLease();
            MemoryEntry normalized = memoryService.remember(candidate);
            persisted.add(memoryRepository.upsert(toFact(extraction, normalized)));
        }

        updateTaskProgress(context, "finalizing", 90);
        MemoryExtractionTaskRecord current = requireExtraction(extraction.publicId());
        Map<String, Object> qualitySummary = qualitySummary(result, persisted);
        context.requireValidLease();
        if (!extractionRepository.complete(
                current.publicId(), current.version(), 1, persisted.size(), qualitySummary, Instant.now())) {
            throw new IllegalStateException("Memory extraction completion lost its optimistic lock");
        }
        return new HandlerResult(Map.of(
                "extractionId", extraction.publicId(),
                "memoryCount", persisted.size(),
                "discardedCount", result.discarded(),
                "mode", result.mode(),
                "memoryIds", persisted.stream().map(MemoryEntryRecord::publicId).toList()));
    }

    private void updateTaskProgress(AsyncTaskContext context, String stage, int progress) {
        context.requireValidLease();
        if (!taskRepository.updateProgress(
                context.task().publicId(),
                context.ownerToken(),
                context.fencingToken(),
                stage,
                progress,
                Instant.now())) {
            throw new IllegalStateException("Async task progress update lost its ownership fence");
        }
    }

    private MemoryExtractionTaskRecord requireExtraction(String extractionId) {
        return extractionRepository
                .find(extractionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown memory extraction: " + extractionId));
    }

    private static MemoryExtractionTask toExtractionTask(
            MemoryExtractionTaskRecord extraction, Map<String, Object> request) {
        return new MemoryExtractionTask(
                extraction.publicId(),
                enumValue(MemoryType.class, text(request, "memoryType")),
                enumValue(MemoryScope.class, text(request, "scope")),
                text(request, "subject"),
                text(request, "content"),
                optionalText(request.get("service")),
                optionalText(request.get("resource")),
                optionalText(request.get("fingerprint")),
                stringMap(request.get("metadata")),
                0,
                extraction.createdAt());
    }

    private static UpsertMemory toFact(MemoryExtractionTaskRecord extraction, MemoryEntry entry) {
        String contentChecksum = sha256(entry.content());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("subject", entry.subject());
        evidence.put("content", entry.content());
        evidence.put("scope", entry.scope().name());
        putIfPresent(evidence, "service", entry.service());
        putIfPresent(evidence, "resource", entry.resource());
        putIfPresent(evidence, "fingerprint", entry.fingerprint());
        evidence.put("metadata", entry.metadata());

        String sourceType = extraction.sourceType().toUpperCase(Locale.ROOT);
        return new UpsertMemory(
                "mem_" + sha256(entry.type().name() + ":" + entry.content()).substring(0, 32),
                entry.type().name(),
                "SESSION".equals(sourceType) ? extraction.sourcePublicId() : null,
                "EXECUTION".equals(sourceType) ? extraction.sourcePublicId() : null,
                "ALARM".equals(sourceType) ? extraction.sourcePublicId() : null,
                evidence,
                quality(entry),
                contentChecksum,
                null,
                entry.id(),
                entry.updatedAt(),
                instant(entry.metadata().get("expires_at")));
    }

    private static Map<String, Object> qualitySummary(
            MemoryExtractionPipeline.ExtractionResult result, List<MemoryEntryRecord> persisted) {
        BigDecimal average = persisted.stream()
                .map(MemoryEntryRecord::qualityScore)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!persisted.isEmpty()) {
            average = average.divide(BigDecimal.valueOf(persisted.size()), 5, RoundingMode.HALF_UP);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", result.mode());
        summary.put("discardedCount", result.discarded());
        summary.put("persistedCount", persisted.size());
        summary.put("averageQuality", average);
        return summary;
    }

    private static BigDecimal quality(MemoryEntry entry) {
        String raw = entry.metadata().get("quality_score");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0 ? null : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String text(Map<String, Object> values, String key) {
        String value = optionalText(values.get(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String optionalText(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, entry) -> {
            String normalizedKey = optionalText(key);
            String normalizedValue = optionalText(entry);
            if (normalizedKey != null && normalizedValue != null) {
                result.put(normalizedKey, normalizedValue);
            }
        });
        return Map.copyOf(result);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value, ex);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
