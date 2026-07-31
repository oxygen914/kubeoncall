package com.kubeoncall.web.api.v1.knowledge;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.knowledge.KnowledgeDocumentCommandService;
import com.kubeoncall.knowledge.KnowledgeDocumentCommandService.MutationOutcome;
import com.kubeoncall.knowledge.KnowledgeImportContentNormalizer;
import com.kubeoncall.knowledge.KnowledgeImportSubmissionService;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRepository;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentVersionRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

/** Versioned Knowledge API backed by the WBS-9 MySQL facts and durable import worker. */
@RestController("v1KnowledgeController")
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final ObjectProvider<KnowledgeDocumentRepository> documentRepositoryProvider;
    private final ObjectProvider<KnowledgeImportRepository> importRepositoryProvider;
    private final ObjectProvider<KnowledgeDocumentCommandService> commandServiceProvider;
    private final ObjectProvider<KnowledgeImportSubmissionService> submissionServiceProvider;
    private final KnowledgeImportContentNormalizer contentNormalizer;
    private final V1Security security;

    public KnowledgeController(
            ObjectProvider<KnowledgeDocumentRepository> documentRepositoryProvider,
            ObjectProvider<KnowledgeImportRepository> importRepositoryProvider,
            ObjectProvider<KnowledgeDocumentCommandService> commandServiceProvider,
            ObjectProvider<KnowledgeImportSubmissionService> submissionServiceProvider,
            KnowledgeImportContentNormalizer contentNormalizer,
            V1Security security) {
        this.documentRepositoryProvider = documentRepositoryProvider;
        this.importRepositoryProvider = importRepositoryProvider;
        this.commandServiceProvider = commandServiceProvider;
        this.submissionServiceProvider = submissionServiceProvider;
        this.contentNormalizer = contentNormalizer;
        this.security = security;
    }

    @GetMapping("/documents")
    public PageMeta.ListEnvelope<DocumentView> documents(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sourceType", required = false) String sourceType,
            @RequestParam(name = "datasetVersion", required = false) String datasetVersion,
            @RequestParam(name = "query", required = false) String text,
            @RequestParam(name = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.KNOWLEDGE_READ);
        KnowledgeDocumentRepository.DocumentPage result = documents()
                .listDocuments(new KnowledgeDocumentRepository.DocumentQuery(
                        status, sourceType, datasetVersion, text, includeDeleted, page, size));
        return PageMeta.ListEnvelope.of(
                result.items().stream().map(DocumentView::listItem).toList(),
                Math.max(1, page),
                normalizedSize(size),
                result.total(),
                requestId());
    }

    @GetMapping("/documents/{documentId}")
    public ApiResponse<DocumentView> document(
            @PathVariable String documentId,
            @RequestParam(name = "includeDeleted", defaultValue = "false") boolean includeDeleted) {
        security.requirePermission(PermissionCode.KNOWLEDGE_READ);
        KnowledgeDocumentRepository repository = documents();
        KnowledgeDocumentRecord document = repository
                .findDocument(documentId, includeDeleted)
                .orElseThrow(() -> notFound("Knowledge document", documentId));
        return ApiResponse.ok(detailView(repository, document), requestId());
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<DocumentView> delete(
            @PathVariable String documentId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) DeleteRequest body,
            HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.KNOWLEDGE_DELETE);
        KnowledgeDocumentCommandService service = commands();
        long version = parseIfMatch(ifMatch);
        MutationOutcome outcome = service.softDelete(
                documentId,
                version,
                body == null ? null : body.reason(),
                new KnowledgeDocumentCommandService.Actor(
                        principal.user().id(),
                        principal.user().displayName(),
                        requestId(),
                        clientIp(request),
                        request.getHeader(HttpHeaders.USER_AGENT)));
        ensureMutation(outcome, documentId);
        KnowledgeDocumentRepository repository = documents();
        KnowledgeDocumentRecord updated =
                repository.findDocument(documentId, true).orElseThrow(() -> notFound("Knowledge document", documentId));
        return ApiResponse.ok(detailView(repository, updated), requestId());
    }

    @PostMapping("/documents/{documentId}/restore")
    public ApiResponse<DocumentView> restore(
            @PathVariable String documentId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.KNOWLEDGE_WRITE);
        long version = parseIfMatch(ifMatch);
        MutationOutcome outcome = commands()
                .restore(
                        documentId,
                        version,
                        new KnowledgeDocumentCommandService.Actor(
                                principal.user().id(),
                                principal.user().displayName(),
                                requestId(),
                                clientIp(request),
                                request.getHeader(HttpHeaders.USER_AGENT)));
        ensureMutation(outcome, documentId);
        KnowledgeDocumentRepository repository = documents();
        KnowledgeDocumentRecord updated = repository
                .findDocument(documentId, false)
                .orElseThrow(() -> notFound("Knowledge document", documentId));
        return ApiResponse.ok(detailView(repository, updated), requestId());
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportAccepted>> createImport(
            @RequestPart("file") MultipartFile file,
            @Parameter(schema = @Schema(allowableValues = {"DOCUMENT", "JSONL", "RUNBOOK"}))
                    @RequestParam(name = "importType", defaultValue = "JSONL")
                    String importType,
            @RequestParam(name = "duplicatePolicy", defaultValue = "SKIP") String duplicatePolicy,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestParam(name = "datasetVersion", required = false) String datasetVersion,
            @RequestParam(name = "metadata", required = false) String metadata,
            HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.KNOWLEDGE_WRITE);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw invalidRequest("Uploaded knowledge file cannot be read", ex);
        }
        KnowledgeImportContentNormalizer.NormalizedImport normalized;
        try {
            normalized =
                    contentNormalizer.normalize(importType, file.getOriginalFilename(), file.getContentType(), content);
        } catch (IllegalArgumentException ex) {
            throw invalidRequest(ex.getMessage(), ex);
        }
        KnowledgeImportSubmissionService.Submission submission = submissions()
                .submit(
                        new KnowledgeImportSubmissionService.Upload(
                                normalized.importType(),
                                normalized.originalFilename(),
                                normalized.jsonlContent(),
                                duplicatePolicy,
                                dryRun,
                                datasetVersion),
                        new KnowledgeImportSubmissionService.Actor(
                                principal.user().id(),
                                principal.user().displayName(),
                                requestId(),
                                clientIp(request),
                                request.getHeader(HttpHeaders.USER_AGENT)));
        ImportAccepted accepted = new ImportAccepted(
                submission.importRecord().publicId(),
                submission.taskPublicId(),
                submission.importRecord().status());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.created(accepted, requestId()));
    }

    @GetMapping("/imports")
    public PageMeta.ListEnvelope<ImportView> imports(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "importType", required = false) String importType,
            @RequestParam(name = "datasetVersion", required = false) String datasetVersion,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.KNOWLEDGE_READ);
        KnowledgeImportRepository.ImportPage result = imports()
                .list(new KnowledgeImportRepository.ImportQuery(status, importType, datasetVersion, page, size));
        return PageMeta.ListEnvelope.of(
                result.items().stream().map(ImportView::from).toList(),
                Math.max(1, page),
                normalizedSize(size),
                result.total(),
                requestId());
    }

    @GetMapping("/imports/{importId}")
    public ApiResponse<ImportView> importDetail(@PathVariable String importId) {
        security.requirePermission(PermissionCode.KNOWLEDGE_READ);
        KnowledgeImportRecord record =
                imports().find(importId).orElseThrow(() -> notFound("Knowledge import", importId));
        return ApiResponse.ok(ImportView.from(record), requestId());
    }

    private KnowledgeDocumentRepository documents() {
        KnowledgeDocumentRepository repository = documentRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw unavailable("Knowledge document read model is not available");
        }
        return repository;
    }

    private KnowledgeImportRepository imports() {
        KnowledgeImportRepository repository = importRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw unavailable("Knowledge import read model is not available");
        }
        return repository;
    }

    private KnowledgeDocumentCommandService commands() {
        KnowledgeDocumentCommandService service = commandServiceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            throw unavailable("Knowledge command service is not available");
        }
        return service;
    }

    private KnowledgeImportSubmissionService submissions() {
        KnowledgeImportSubmissionService service = submissionServiceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            throw unavailable("Knowledge import service is not available");
        }
        return service;
    }

    private V1Principal requireUser(String permission) {
        V1Principal principal = security.requirePermission(permission);
        if (principal.user() == null || principal.user().id() <= 0) {
            throw V1ApiException.forbidden("A user-backed session is required for this command");
        }
        return principal;
    }

    private static DocumentView detailView(KnowledgeDocumentRepository repository, KnowledgeDocumentRecord document) {
        return DocumentView.detail(document, repository.listVersions(document.publicId()));
    }

    private static void ensureMutation(MutationOutcome outcome, String documentId) {
        switch (outcome) {
            case UPDATED -> {
                return;
            }
            case NOT_FOUND -> throw notFound("Knowledge document", documentId);
            case VERSION_CONFLICT ->
                throw V1ApiException.conflict(
                        V1ApiErrorCode.RESOURCE_VERSION_CONFLICT, "Knowledge document version changed");
            case INVALID_STATE ->
                throw V1ApiException.conflict(
                        V1ApiErrorCode.CONFLICT, "Knowledge document is already in the requested lifecycle state");
        }
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match version header is required");
        }
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("If-Match must be a numeric version", ex);
        }
    }

    private static int normalizedSize(int size) {
        return Math.max(1, Math.min(size, 200));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String requestId() {
        return RequestIdFilter.currentRequestId();
    }

    private static V1ApiException notFound(String type, String publicId) {
        return V1ApiException.notFound(type + " not found: " + publicId);
    }

    private static V1ApiException unavailable(String message) {
        return new V1ApiException(HttpStatus.SERVICE_UNAVAILABLE.value(), V1ApiErrorCode.SERVICE_UNAVAILABLE, message);
    }

    private static V1ApiException invalidRequest(String message, Exception cause) {
        return new V1ApiException(
                HttpStatus.BAD_REQUEST.value(),
                V1ApiErrorCode.INVALID_REQUEST,
                message == null ? "Invalid import request" : message);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentView(
            String id,
            String externalDocumentId,
            String title,
            String sourceType,
            String sourceUri,
            String datasetVersion,
            String status,
            Map<String, Object> metadata,
            String currentVersionId,
            long version,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            java.time.Instant deletedAt,
            String deleteReason,
            List<VersionView> versions) {

        private static DocumentView listItem(KnowledgeDocumentRecord record) {
            return from(record, null);
        }

        private static DocumentView detail(
                KnowledgeDocumentRecord record, List<KnowledgeDocumentVersionRecord> versions) {
            return from(record, versions.stream().map(VersionView::from).toList());
        }

        private static DocumentView from(KnowledgeDocumentRecord record, List<VersionView> versions) {
            return new DocumentView(
                    record.publicId(),
                    record.externalDocumentId(),
                    record.title(),
                    record.sourceType(),
                    record.sourceUri(),
                    record.datasetVersion(),
                    record.status(),
                    record.metadata(),
                    record.currentVersionPublicId(),
                    record.version(),
                    record.createdAt(),
                    record.updatedAt(),
                    record.deletedAt(),
                    record.deleteReason(),
                    versions);
        }
    }

    public record VersionView(
            String id,
            String documentId,
            String importId,
            int versionNumber,
            String checksum,
            String contentType,
            long sizeBytes,
            String objectBucket,
            String objectKey,
            String esIndex,
            String esDocumentId,
            String indexStatus,
            java.time.Instant indexedAt,
            java.time.Instant createdAt) {

        private static VersionView from(KnowledgeDocumentVersionRecord record) {
            return new VersionView(
                    record.publicId(),
                    record.documentPublicId(),
                    record.importPublicId(),
                    record.versionNumber(),
                    record.checksum(),
                    record.contentType(),
                    record.sizeBytes(),
                    record.objectBucket(),
                    record.objectKey(),
                    record.esIndex(),
                    record.esDocumentId(),
                    record.indexStatus(),
                    record.indexedAt(),
                    record.createdAt());
        }
    }

    public record ImportView(
            String id,
            String taskId,
            String importType,
            String duplicatePolicy,
            boolean dryRun,
            String status,
            long sourceSizeBytes,
            String datasetVersion,
            Long totalCount,
            long processedCount,
            long succeededCount,
            long failedCount,
            long skippedCount,
            String errorCode,
            String errorSummary,
            java.time.Instant startedAt,
            java.time.Instant finishedAt,
            long version,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {

        private static ImportView from(KnowledgeImportRecord record) {
            return new ImportView(
                    record.publicId(),
                    record.taskPublicId(),
                    record.importType(),
                    record.duplicatePolicy(),
                    record.dryRun(),
                    record.status(),
                    record.sourceSizeBytes(),
                    record.datasetVersion(),
                    record.totalCount(),
                    record.processedCount(),
                    record.succeededCount(),
                    record.failedCount(),
                    record.skippedCount(),
                    record.errorCode(),
                    record.errorSummary(),
                    record.startedAt(),
                    record.finishedAt(),
                    record.version(),
                    record.createdAt(),
                    record.updatedAt());
        }
    }

    public record ImportAccepted(String importId, String taskId, String status) {}

    public record DeleteRequest(String reason) {}
}
