package com.kubeoncall.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRepository;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentVersionRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.rag.KnowledgeJsonlImportService;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;

/**
 * Retry-safe canonical JSONL import handler for JSONL, document and runbook uploads.
 *
 * <p>Input errors become a partial-result report. A syntactically valid line that fails inside the
 * existing ES ingestion service is treated as an infrastructure failure and is rethrown so the
 * generic worker schedules RETRY instead of publishing false success.
 */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class KnowledgeImportTaskHandler implements AsyncTaskHandler {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KnowledgeObjectStorageService objectStorage;
    private final KnowledgeJsonlImportService jsonlImportService;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeImportRepository importRepository;
    private final AsyncTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public KnowledgeImportTaskHandler(
            KnowledgeObjectStorageService objectStorage,
            KnowledgeJsonlImportService jsonlImportService,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeImportRepository importRepository,
            AsyncTaskRepository taskRepository,
            ObjectMapper objectMapper,
            KubeOnCallProperties properties) {
        this.objectStorage = objectStorage;
        this.jsonlImportService = jsonlImportService;
        this.documentRepository = documentRepository;
        this.importRepository = importRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String taskType() {
        return "KNOWLEDGE_IMPORT";
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        KnowledgeImportRecord importRecord = importRepository
                .find(context.task().resourcePublicId())
                .orElseThrow(() -> new IllegalStateException("Knowledge import metadata is missing"));
        String payload = objectStorage.readText(importRecord.sourceBucket(), importRecord.sourceObjectKey());
        if (!sha256(payload.getBytes(StandardCharsets.UTF_8)).equals(importRecord.sourceChecksum())) {
            throw new IllegalStateException("Knowledge import source checksum mismatch");
        }

        List<SourceLine> lines = sourceLines(payload);
        Counters counters = new Counters();
        List<LineError> errors = new ArrayList<>();
        KnowledgeImportRecord current = importRecord;
        for (int index = 0; index < lines.size(); index++) {
            SourceLine line = lines.get(index);
            context.requireValidLease();
            try {
                LineOutcome outcome = processLine(line, current, context);
                if (outcome == LineOutcome.SUCCEEDED) {
                    counters.succeeded++;
                } else {
                    counters.skipped++;
                }
            } catch (InvalidLineException invalid) {
                counters.failed++;
                errors.add(new LineError(line.number(), invalid.getMessage()));
            }
            counters.processed++;
            current = updateProgress(context, current, counters, lines.size());
        }

        StoredDocumentReference errorReport = null;
        if (!errors.isEmpty()) {
            errorReport = objectStorage.storeErrorReport(current.publicId(), errorReport(errors));
        }
        context.requireValidLease();
        boolean completed = importRepository.complete(
                current.publicId(),
                current.version(),
                counters.processed,
                counters.succeeded,
                counters.failed,
                counters.skipped,
                errorReport == null ? null : errorReport.bucket(),
                errorReport == null ? null : errorReport.objectKey(),
                Instant.now());
        if (!completed) {
            throw new IllegalStateException("Knowledge import completion lost its version fence");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("importId", current.publicId());
        result.put("processed", counters.processed);
        result.put("succeeded", counters.succeeded);
        result.put("failed", counters.failed);
        result.put("skipped", counters.skipped);
        if (errorReport != null) {
            result.put("errorReportBucket", errorReport.bucket());
            result.put("errorReportObjectKey", errorReport.objectKey());
        }
        return new HandlerResult(result);
    }

    private LineOutcome processLine(
            SourceLine sourceLine, KnowledgeImportRecord importRecord, AsyncTaskContext context) {
        ParsedLine line = parse(sourceLine, importRecord.datasetVersion());
        String checksum = sha256(sourceLine.content().getBytes(StandardCharsets.UTF_8));
        String documentPublicId = publicDocumentId(importRecord.importType(), line.externalDocumentId());
        KnowledgeDocumentRecord existing = documentRepository
                .findDocumentByExternalId(importRecord.importType(), line.externalDocumentId(), true)
                .orElse(null);
        KnowledgeDocumentVersionRecord sameVersion = existing == null
                ? null
                : documentRepository.listVersions(existing.publicId()).stream()
                        .filter(version -> checksum.equals(version.checksum()))
                        .findFirst()
                        .orElse(null);
        if (sameVersion != null && importRecord.publicId().equals(sameVersion.importPublicId())) {
            return LineOutcome.SUCCEEDED;
        }
        if (existing != null && "SKIP".equals(importRecord.duplicatePolicy())) {
            return LineOutcome.SKIPPED;
        }
        if (existing != null && "FAIL".equals(importRecord.duplicatePolicy())) {
            throw new InvalidLineException("Duplicate external document id");
        }
        if (importRecord.dryRun()) {
            return existing == null ? LineOutcome.SUCCEEDED : LineOutcome.SKIPPED;
        }
        if (sameVersion != null) {
            return LineOutcome.SUCCEEDED;
        }

        context.requireValidLease();
        KnowledgeJsonlImportService.ImportResult result = jsonlImportService.importJsonl(line.normalizedJson());
        if (result.imported() != 1 || result.failed() != 0 || result.lines().isEmpty()) {
            throw new IllegalStateException("Knowledge indexing failed for JSONL line " + sourceLine.number());
        }
        String esDocumentId = result.lines().get(0).documentId();
        KnowledgeDocumentRecord document = existing;
        if (document == null) {
            document = documentRepository.createDocument(new KnowledgeDocumentRepository.CreateDocument(
                    documentPublicId,
                    line.externalDocumentId(),
                    line.title(),
                    importRecord.importType(),
                    "minio://" + importRecord.sourceBucket() + "/" + importRecord.sourceObjectKey() + "#line="
                            + sourceLine.number(),
                    importRecord.datasetVersion(),
                    line.metadata(),
                    null));
        } else if (document.deletedAt() != null
                && !documentRepository.restore(document.publicId(), document.version())) {
            throw new IllegalStateException("Deleted knowledge document could not be restored");
        }
        documentRepository.createVersion(new KnowledgeDocumentRepository.CreateVersion(
                versionPublicId(document.publicId(), checksum),
                document.publicId(),
                importRecord.publicId(),
                checksum,
                "application/json",
                sourceLine.content().getBytes(StandardCharsets.UTF_8).length,
                importRecord.sourceBucket(),
                importRecord.sourceObjectKey(),
                "INDEXED",
                null,
                null,
                null,
                null,
                null,
                Map.of("line", sourceLine.number(), "externalDocumentId", line.externalDocumentId())));
        return LineOutcome.SUCCEEDED;
    }

    private KnowledgeImportRecord updateProgress(
            AsyncTaskContext context, KnowledgeImportRecord current, Counters counters, int totalLines) {
        int progress = totalLines == 0 ? 0 : Math.min(99, counters.processed * 99 / totalLines);
        if (!taskRepository.updateProgress(
                context.task().publicId(),
                context.ownerToken(),
                context.fencingToken(),
                "indexing",
                progress,
                Instant.now())) {
            throw new IllegalStateException("Knowledge task progress lost its ownership fence");
        }
        if (!importRepository.updateProgress(
                current.publicId(),
                current.version(),
                (long) totalLines,
                counters.processed,
                counters.succeeded,
                counters.failed,
                counters.skipped,
                Instant.now())) {
            throw new IllegalStateException("Knowledge import progress lost its version fence");
        }
        return importRepository
                .find(current.publicId())
                .orElseThrow(() -> new IllegalStateException("Knowledge import disappeared during processing"));
    }

    private ParsedLine parse(SourceLine sourceLine, String datasetVersion) {
        try {
            LinkedHashMap<String, Object> item = objectMapper.readValue(sourceLine.content(), MAP_TYPE);
            String title = requiredText(item, "title");
            String content = requiredText(item, "content");
            if (content.getBytes(StandardCharsets.UTF_8).length
                    > Math.max(1, properties.getRag().getDocumentMaxContentBytes())) {
                throw new InvalidLineException("Knowledge document content exceeds the configured byte limit");
            }
            String externalId = text(item.get("doc_id"));
            if (externalId.isBlank()) {
                externalId = text(item.get("id"));
            }
            if (externalId.isBlank()) {
                externalId = sha256((title + "\n" + content).getBytes(StandardCharsets.UTF_8));
            }
            if (externalId.length() > 255) {
                throw new InvalidLineException("External document id exceeds 255 characters");
            }
            item.put("doc_id", externalId);
            if (datasetVersion != null && !datasetVersion.isBlank()) {
                item.put("dataset_version", datasetVersion);
            }
            return new ParsedLine(title, externalId, metadata(item), objectMapper.writeValueAsString(item));
        } catch (InvalidLineException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidLineException("Invalid JSONL document");
        }
    }

    private Map<String, Object> metadata(Map<String, Object> item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object nested = item.get("metadata");
        if (nested instanceof Map<?, ?> map) {
            map.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        }
        putIfPresent(metadata, "dataset_version", item.get("dataset_version"));
        putIfPresent(metadata, "document_type", item.get("document_type"));
        putIfPresent(metadata, "source_type", item.get("source_type"));
        return metadata;
    }

    private List<SourceLine> sourceLines(String payload) {
        String[] rawLines = payload == null ? new String[0] : payload.split("\\R");
        List<SourceLine> lines = new ArrayList<>();
        for (int index = 0; index < rawLines.length; index++) {
            String content = rawLines[index].trim();
            if (!content.isBlank()) {
                lines.add(new SourceLine(index + 1, content));
            }
        }
        return List.copyOf(lines);
    }

    private String errorReport(List<LineError> errors) {
        StringBuilder report = new StringBuilder();
        for (LineError error : errors) {
            try {
                report.append(objectMapper.writeValueAsString(
                                Map.of("line", error.line(), "status", "failed", "message", error.message())))
                        .append('\n');
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to serialize knowledge import error report", ex);
            }
        }
        return report.toString();
    }

    private static String requiredText(Map<String, Object> item, String key) {
        String value = text(item.get(key));
        if (value.isBlank()) {
            throw new InvalidLineException(key + " must not be blank");
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private static String publicDocumentId(String importType, String externalId) {
        return "doc_"
                + sha256((importType + ":" + externalId).getBytes(StandardCharsets.UTF_8))
                        .substring(0, 32);
    }

    private static String versionPublicId(String documentPublicId, String checksum) {
        return "docv_"
                + sha256((documentPublicId + ":" + checksum).getBytes(StandardCharsets.UTF_8))
                        .substring(0, 31);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash knowledge content", ex);
        }
    }

    private enum LineOutcome {
        SUCCEEDED,
        SKIPPED
    }

    private static final class Counters {
        private int processed;
        private int succeeded;
        private int failed;
        private int skipped;
    }

    private record SourceLine(int number, String content) {}

    private record ParsedLine(
            String title, String externalDocumentId, Map<String, Object> metadata, String normalizedJson) {}

    private record LineError(int line, String message) {}

    private static final class InvalidLineException extends RuntimeException {

        private InvalidLineException(String message) {
            super(message);
        }
    }
}
