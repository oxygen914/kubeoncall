package com.kubeoncall.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.audit.OutboxWriter.OutboxEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;

/**
 * Uploads a JSONL source first, then atomically creates its import/task/audit/outbox facts.
 *
 * <p>A normal database rollback removes the just-uploaded object. Process-death orphan cleanup is
 * intentionally a separate maintenance concern.
 */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class KnowledgeImportSubmissionService {

    private final KnowledgeObjectStorageService objectStorage;
    private final AsyncTaskRepository taskRepository;
    private final KnowledgeImportRepository importRepository;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;
    private final KubeOnCallProperties properties;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeImportSubmissionService(
            KnowledgeObjectStorageService objectStorage,
            AsyncTaskRepository taskRepository,
            KnowledgeImportRepository importRepository,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            KubeOnCallProperties properties,
            PlatformTransactionManager transactionManager) {
        this.objectStorage = objectStorage;
        this.taskRepository = taskRepository;
        this.importRepository = importRepository;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Submission submit(Upload upload, Actor actor) {
        validate(upload);
        String duplicatePolicy = normalizePolicy(upload.duplicatePolicy());
        String importPublicId = "imp_" + compactUuid();
        String taskPublicId = "tsk_" + compactUuid();
        String checksum = sha256(upload.content());
        long totalCount = nonBlankLineCount(upload.content());
        StoredDocumentReference source =
                objectStorage.storeJsonl(importPublicId, upload.originalFilename(), upload.content());
        try {
            Submission submission = transactionTemplate.execute(status -> {
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("importPublicId", importPublicId);
                request.put("sourceBucket", source.bucket());
                request.put("sourceObjectKey", source.objectKey());
                request.put("sourceChecksum", checksum);
                request.put("duplicatePolicy", duplicatePolicy);
                request.put("dryRun", upload.dryRun());
                putIfPresent(request, "datasetVersion", upload.datasetVersion());
                AsyncTaskRecord task = taskRepository.create(new AsyncTaskRepository.CreateTask(
                        taskPublicId,
                        "KNOWLEDGE_IMPORT",
                        "KNOWLEDGE_IMPORT",
                        importPublicId,
                        checksum + ":" + duplicatePolicy + ":" + upload.dryRun(),
                        "queued",
                        request,
                        5,
                        Instant.now(),
                        actor.requestId(),
                        null));
                KnowledgeImportRecord importRecord = importRepository.create(new KnowledgeImportRepository.CreateImport(
                        importPublicId,
                        task.publicId(),
                        "JSONL",
                        duplicatePolicy,
                        upload.dryRun(),
                        source.bucket(),
                        source.objectKey(),
                        checksum,
                        upload.content().length,
                        upload.datasetVersion(),
                        totalCount));
                auditWriter.write(OperationAuditWriter.builder()
                        .actor("USER", actor.userId(), actor.displayName())
                        .action("knowledge.import.create")
                        .resource("KNOWLEDGE_IMPORT", importPublicId)
                        .result("SUCCESS")
                        .after(Map.of(
                                "taskId", task.publicId(),
                                "checksum", checksum,
                                "dryRun", upload.dryRun()))
                        .requestId(actor.requestId())
                        .sourceIp(actor.sourceIp())
                        .userAgent(actor.userAgent())
                        .build());
                outboxWriter.enqueue(OutboxEvent.of(
                        "task",
                        task.publicId(),
                        "task.created",
                        Map.of(
                                "taskId", task.publicId(),
                                "taskType", task.taskType(),
                                "resourceType", task.resourceType(),
                                "resourceId", importPublicId,
                                "status", task.status()),
                        actor.requestId()));
                return new Submission(importRecord, task.publicId());
            });
            if (submission == null) {
                throw new IllegalStateException("Knowledge import transaction returned no result");
            }
            return submission;
        } catch (RuntimeException failure) {
            objectStorage.remove(source);
            throw failure;
        }
    }

    public boolean isAvailable() {
        return taskRepository.isAvailable() && importRepository.isAvailable();
    }

    private void validate(Upload upload) {
        if (upload == null || upload.content() == null || upload.content().length == 0) {
            throw new IllegalArgumentException("A non-empty JSONL file is required");
        }
        int limit = Math.max(1, properties.getRag().getJsonlMaxPayloadBytes());
        if (upload.content().length > limit) {
            throw new IllegalArgumentException("JSONL file exceeds the configured byte limit");
        }
        int maxLines = Math.max(1, properties.getRag().getJsonlMaxLines());
        if (nonBlankLineCount(upload.content()) > maxLines) {
            throw new IllegalArgumentException("JSONL file exceeds the configured line limit");
        }
    }

    private static String normalizePolicy(String value) {
        String policy = value == null || value.isBlank() ? "SKIP" : value.trim().toUpperCase();
        if (!"SKIP".equals(policy) && !"REPLACE".equals(policy) && !"FAIL".equals(policy)) {
            throw new IllegalArgumentException("duplicatePolicy must be SKIP, REPLACE or FAIL");
        }
        return policy;
    }

    private static long nonBlankLineCount(byte[] content) {
        return new String(content, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .count();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash knowledge import", ex);
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    public record Upload(
            String originalFilename, byte[] content, String duplicatePolicy, boolean dryRun, String datasetVersion) {

        public Upload {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    public record Actor(long userId, String displayName, String requestId, String sourceIp, String userAgent) {}

    public record Submission(KnowledgeImportRecord importRecord, String taskPublicId) {}
}
