package com.kubeoncall.knowledge;

import java.time.Instant;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.audit.OutboxWriter.OutboxEvent;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRepository;
import com.kubeoncall.rag.KnowledgeIngestService;

/** Versioned knowledge lifecycle commands with audit/outbox facts in the MySQL transaction. */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class KnowledgeDocumentCommandService {

    private final KnowledgeDocumentRepository repository;
    private final KnowledgeIngestService ingestService;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;

    public KnowledgeDocumentCommandService(
            KnowledgeDocumentRepository repository,
            KnowledgeIngestService ingestService,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter) {
        this.repository = repository;
        this.ingestService = ingestService;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public MutationOutcome softDelete(String publicId, long expectedVersion, String reason, Actor actor) {
        KnowledgeDocumentRecord current =
                repository.findDocument(publicId, true).orElse(null);
        MutationOutcome validation = validate(current, expectedVersion, false);
        if (validation != null) {
            return validation;
        }
        if (!repository.softDelete(publicId, expectedVersion, actor.userId(), reason, Instant.now())) {
            return MutationOutcome.VERSION_CONFLICT;
        }
        changeSearchAvailability(current, false, reason);
        writeFacts(current, "knowledge.document.delete", "knowledge.document.deleted", reason, actor);
        return MutationOutcome.UPDATED;
    }

    @Transactional
    public MutationOutcome restore(String publicId, long expectedVersion, Actor actor) {
        KnowledgeDocumentRecord current =
                repository.findDocument(publicId, true).orElse(null);
        MutationOutcome validation = validate(current, expectedVersion, true);
        if (validation != null) {
            return validation;
        }
        if (!repository.restore(publicId, expectedVersion)) {
            return MutationOutcome.VERSION_CONFLICT;
        }
        changeSearchAvailability(current, true, null);
        writeFacts(current, "knowledge.document.restore", "knowledge.document.restored", null, actor);
        return MutationOutcome.UPDATED;
    }

    public boolean isAvailable() {
        return repository.isAvailable();
    }

    private MutationOutcome validate(KnowledgeDocumentRecord current, long expectedVersion, boolean restoring) {
        if (current == null) {
            return MutationOutcome.NOT_FOUND;
        }
        if (current.version() != expectedVersion) {
            return MutationOutcome.VERSION_CONFLICT;
        }
        if (restoring && current.deletedAt() == null) {
            return MutationOutcome.INVALID_STATE;
        }
        if (!restoring && current.deletedAt() != null) {
            return MutationOutcome.INVALID_STATE;
        }
        return null;
    }

    private void changeSearchAvailability(KnowledgeDocumentRecord current, boolean restore, String reason) {
        if (current.currentVersionPublicId() == null) {
            return;
        }
        repository.findVersion(current.currentVersionPublicId()).ifPresent(version -> {
            String searchId = version.esDocumentId();
            if (searchId == null || searchId.isBlank()) {
                return;
            }
            if (restore) {
                ingestService.restore(searchId);
            } else {
                ingestService.softDelete(searchId, reason);
            }
        });
    }

    private void writeFacts(
            KnowledgeDocumentRecord current, String action, String eventType, String reason, Actor actor) {
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", actor.userId(), actor.displayName())
                .action(action)
                .resource("KNOWLEDGE_DOCUMENT", current.publicId())
                .result("SUCCESS")
                .reason(reason)
                .before(Map.of("status", current.status(), "version", current.version()))
                .after(Map.of(
                        "status",
                        eventType.endsWith("restored") ? "ACTIVE" : "DELETED",
                        "version",
                        current.version() + 1))
                .requestId(actor.requestId())
                .sourceIp(actor.sourceIp())
                .userAgent(actor.userAgent())
                .build());
        outboxWriter.enqueue(OutboxEvent.of(
                "knowledge_document",
                current.publicId(),
                eventType,
                Map.of("documentId", current.publicId(), "version", current.version() + 1),
                actor.requestId()));
    }

    public enum MutationOutcome {
        UPDATED,
        NOT_FOUND,
        VERSION_CONFLICT,
        INVALID_STATE
    }

    public record Actor(long userId, String displayName, String requestId, String sourceIp, String userAgent) {}
}
