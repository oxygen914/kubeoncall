package com.kubeoncall.web;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.rag.KnowledgeIngestionFacade;
import com.kubeoncall.rag.KnowledgeJsonlImportService;
import com.kubeoncall.rag.repository.KnowledgeIndexAdmin;
import com.kubeoncall.rag.runbook.RunbookImportService;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.web.dto.KnowledgeIngestRequest;
import com.kubeoncall.web.dto.KnowledgeQueryRequest;
import com.kubeoncall.web.dto.RunbookImportRequest;

@LegacyApiController
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestService knowledgeIngestService;
    private final KnowledgeJsonlImportService knowledgeJsonlImportService;
    private final RunbookImportService runbookImportService;
    private final KnowledgeIndexAdmin knowledgeIndexAdmin;
    private final ExecutionAuditService executionAuditService;
    private final KubeOnCallMetricsService metricsService;

    public KnowledgeController(
            KnowledgeIngestService knowledgeIngestService,
            KnowledgeJsonlImportService knowledgeJsonlImportService,
            RunbookImportService runbookImportService,
            KnowledgeIndexAdmin knowledgeIndexAdmin,
            ExecutionAuditService executionAuditService,
            KubeOnCallMetricsService metricsService) {
        this.knowledgeIngestService = knowledgeIngestService;
        this.knowledgeJsonlImportService = knowledgeJsonlImportService;
        this.runbookImportService = runbookImportService;
        this.knowledgeIndexAdmin = knowledgeIndexAdmin;
        this.executionAuditService = executionAuditService;
        this.metricsService = metricsService;
    }

    @PostMapping("/ingest")
    public KnowledgeDocument ingest(@Valid @RequestBody KnowledgeIngestRequest request) {
        Instant startedAt = Instant.now();
        try {
            KnowledgeDocument result = knowledgeIngestService.ingest(
                    request.title(), request.content(), request.source(), request.metadata());
            recordKnowledge(
                    "ingest",
                    "SUCCESS",
                    "Knowledge document ingested",
                    startedAt,
                    1,
                    Map.of("documentId", result.id(), "source", safe(result.source()), "title", safe(result.title())));
            return result;
        } catch (RuntimeException ex) {
            recordKnowledgeFailure("ingest", startedAt, ex);
            throw ex;
        }
    }

    @PostMapping("/query")
    public RetrievalResult query(@Valid @RequestBody KnowledgeQueryRequest request) {
        Instant startedAt = Instant.now();
        try {
            RetrieveMethod method = RetrieveMethod.fromRaw(request.retrieveMethod());
            RetrievalResult result = knowledgeIngestService.retrieve(
                    request.question(),
                    request.filters(),
                    request.topK(),
                    method,
                    Boolean.TRUE.equals(request.includeTrace()));
            recordKnowledge(
                    "query",
                    "SUCCESS",
                    "Knowledge query completed",
                    startedAt,
                    result.documents().size(),
                    Map.of(
                            "method", method.name(),
                            "route", safe(result.route()),
                            "resultCount", result.documents().size(),
                            "filterCount",
                                    request.filters() == null
                                            ? 0
                                            : request.filters().size(),
                            "includeTrace", Boolean.TRUE.equals(request.includeTrace())));
            return result;
        } catch (RuntimeException ex) {
            recordKnowledgeFailure("query", startedAt, ex);
            throw ex;
        }
    }

    @PostMapping("/import/jsonl")
    public KnowledgeJsonlImportService.ImportResult importJsonl(@RequestBody(required = false) String payload) {
        Instant startedAt = Instant.now();
        try {
            KnowledgeJsonlImportService.ImportResult result = knowledgeJsonlImportService.importJsonl(payload);
            String status = result.failed() == 0 ? "SUCCESS" : "DEGRADED";
            recordKnowledge(
                    "jsonl_import",
                    status,
                    "Knowledge JSONL import completed",
                    startedAt,
                    result.imported(),
                    Map.of("scanned", result.scanned(), "imported", result.imported(), "failed", result.failed()));
            return result;
        } catch (RuntimeException ex) {
            recordKnowledgeFailure("jsonl_import", startedAt, ex);
            throw ex;
        }
    }

    @PostMapping("/{documentId}/delete")
    public KnowledgeIngestionFacade.LifecycleResult delete(
            @PathVariable String documentId, @RequestBody(required = false) LifecycleRequest request) {
        return lifecycle(
                "delete",
                documentId,
                () -> knowledgeIngestService.softDelete(documentId, request == null ? null : request.reason()));
    }

    @PostMapping("/{documentId}/restore")
    public KnowledgeIngestionFacade.LifecycleResult restore(@PathVariable String documentId) {
        return lifecycle("restore", documentId, () -> knowledgeIngestService.restore(documentId));
    }

    @PostMapping("/runbooks/import")
    public RunbookImportService.ImportResult importRunbooks(
            @RequestBody(required = false) RunbookImportRequest request) {
        Instant startedAt = Instant.now();
        try {
            RunbookImportService.ImportResult result =
                    runbookImportService.importAll(request != null && Boolean.TRUE.equals(request.dryRun()));
            String status = result.failed() == 0 ? "SUCCESS" : "DEGRADED";
            recordKnowledge(
                    "runbook_import",
                    status,
                    "Runbook import completed",
                    startedAt,
                    result.imported(),
                    Map.of(
                            "scanned",
                            result.scanned(),
                            "eligible",
                            result.eligible(),
                            "imported",
                            result.imported(),
                            "skipped",
                            result.skipped(),
                            "failed",
                            result.failed(),
                            "dryRun",
                            result.dryRun()));
            return result;
        } catch (RuntimeException ex) {
            recordKnowledgeFailure("runbook_import", startedAt, ex);
            throw ex;
        }
    }

    @GetMapping("/index")
    public KnowledgeIndexAdmin.AliasStatus indexStatus() {
        return knowledgeIndexAdmin.aliasStatus();
    }

    @PostMapping("/index/prepare")
    public KnowledgeIndexAdmin.VersionPreparation prepareIndex(@RequestBody IndexPrepareRequest request) {
        Instant startedAt = Instant.now();
        KnowledgeIndexAdmin.VersionPreparation result = knowledgeIndexAdmin.prepareVersion(
                request == null ? null : request.version(), request != null && request.reindex());
        audit(
                "KNOWLEDGE_INDEX_PREPARED",
                "Knowledge index version prepared",
                startedAt,
                Map.of(
                        "version",
                        result.version(),
                        "index",
                        result.index(),
                        "reindex",
                        request != null && request.reindex(),
                        "total",
                        result.total()));
        return result;
    }

    @PostMapping("/index/activate/{version}")
    public KnowledgeIndexAdmin.AliasStatus activateIndex(@PathVariable String version) {
        return switchIndex(version, "KNOWLEDGE_INDEX_ACTIVATED");
    }

    @PostMapping("/index/rollback/{version}")
    public KnowledgeIndexAdmin.AliasStatus rollbackIndex(@PathVariable String version) {
        return switchIndex(version, "KNOWLEDGE_INDEX_ROLLED_BACK");
    }

    private KnowledgeIndexAdmin.AliasStatus switchIndex(String version, String operation) {
        Instant startedAt = Instant.now();
        KnowledgeIndexAdmin.AliasStatus result = knowledgeIndexAdmin.activateVersion(version);
        audit(
                operation,
                "Knowledge index alias switched",
                startedAt,
                Map.of("version", version, "activeIndex", result.activeIndex(), "alias", result.alias()));
        return result;
    }

    private void audit(String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        executionAuditService.recordKnowledgeOperation("index_governance", status, summary, startedAt, metadata);
    }

    private void recordKnowledge(
            String operation,
            String status,
            String summary,
            Instant startedAt,
            long count,
            Map<String, Object> metadata) {
        metricsService.recordKnowledge(operation, status, count);
        executionAuditService.recordKnowledgeOperation(operation, status, summary, startedAt, metadata);
    }

    private void recordKnowledgeFailure(String operation, Instant startedAt, RuntimeException ex) {
        recordKnowledge(
                operation,
                "FAILED",
                "Knowledge operation failed",
                startedAt,
                0,
                Map.of("errorType", ex.getClass().getSimpleName()));
    }

    private KnowledgeIngestionFacade.LifecycleResult lifecycle(
            String operation, String documentId, LifecycleOperation lifecycleOperation) {
        Instant startedAt = Instant.now();
        try {
            KnowledgeIngestionFacade.LifecycleResult result = lifecycleOperation.perform();
            recordKnowledge(
                    operation,
                    result.found() ? "SUCCESS" : "NOT_FOUND",
                    "Knowledge lifecycle operation completed",
                    startedAt,
                    result.affected(),
                    Map.of("documentId", safe(documentId), "affected", result.affected()));
            return result;
        } catch (RuntimeException ex) {
            recordKnowledgeFailure(operation, startedAt, ex);
            throw ex;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record IndexPrepareRequest(String version, boolean reindex) {}

    public record LifecycleRequest(String reason) {}

    @FunctionalInterface
    private interface LifecycleOperation {
        KnowledgeIngestionFacade.LifecycleResult perform();
    }
}
