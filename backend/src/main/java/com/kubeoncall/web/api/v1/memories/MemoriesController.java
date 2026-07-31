package com.kubeoncall.web.api.v1.memories;

import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.memory.MemoryGovernanceService;
import com.kubeoncall.memory.MemoryGovernanceService.ChangeCommand;
import com.kubeoncall.memory.MemoryGovernanceService.CommandException;
import com.kubeoncall.memory.MemoryGovernanceService.StartCommand;
import com.kubeoncall.memory.MemoryGovernanceService.StartResult;
import com.kubeoncall.memory.mysql.MemoryEntryRecord;
import com.kubeoncall.memory.mysql.MemoryEntryRepository;
import com.kubeoncall.memory.mysql.MemoryEntryRepository.MemoryPage;
import com.kubeoncall.memory.mysql.MemoryEntryRepository.MemoryQuery;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRecord;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository.ExtractionPage;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository.ExtractionQuery;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta.ListEnvelope;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/** MySQL-backed memory governance and durable extraction-task API. */
@RestController
@RequestMapping("/api/v1/memories")
public class MemoriesController {

    private final ObjectProvider<MemoryEntryRepository> memoryRepositoryProvider;
    private final ObjectProvider<MemoryExtractionTaskRepository> extractionRepositoryProvider;
    private final ObjectProvider<MemoryGovernanceService> governanceServiceProvider;
    private final V1Security security;

    public MemoriesController(
            ObjectProvider<MemoryEntryRepository> memoryRepositoryProvider,
            ObjectProvider<MemoryExtractionTaskRepository> extractionRepositoryProvider,
            ObjectProvider<MemoryGovernanceService> governanceServiceProvider,
            V1Security security) {
        this.memoryRepositoryProvider = memoryRepositoryProvider;
        this.extractionRepositoryProvider = extractionRepositoryProvider;
        this.governanceServiceProvider = governanceServiceProvider;
        this.security = security;
    }

    @GetMapping
    public ListEnvelope<MemoryListView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String memoryType,
            @RequestParam(required = false) String sourceExecutionId,
            @RequestParam(required = false) String sourceAlarmId,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.MEMORY_READ);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 200));
        MemoryPage result = memoryRepository()
                .list(new MemoryQuery(
                        status,
                        memoryType,
                        sourceExecutionId,
                        sourceAlarmId,
                        includeDeleted,
                        activeOnly ? Instant.now() : null,
                        normalizedPage,
                        normalizedSize));
        return ListEnvelope.of(
                result.items().stream().map(MemoryListView::from).toList(),
                normalizedPage,
                normalizedSize,
                result.total(),
                RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{memoryId}")
    public ApiResponse<MemoryView> detail(
            @PathVariable String memoryId, @RequestParam(defaultValue = "false") boolean includeDeleted) {
        security.requirePermission(PermissionCode.MEMORY_READ);
        MemoryEntryRecord memory = memoryRepository()
                .find(memoryId, includeDeleted)
                .orElseThrow(() -> V1ApiException.notFound("Memory not found: " + memoryId));
        return ApiResponse.ok(MemoryView.from(memory), RequestIdFilter.currentRequestId());
    }

    @DeleteMapping("/{memoryId}")
    public ApiResponse<MemoryView> delete(
            @PathVariable String memoryId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody DeleteRequest body,
            HttpServletRequest request) {
        V1Principal principal = security.requirePermission(PermissionCode.MEMORY_MAINTAIN);
        ChangeCommand command = changeCommand(memoryId, parseIfMatch(ifMatch), body.reason(), principal, request);
        return ApiResponse.ok(
                MemoryView.from(runCommand(() -> governanceService().softDelete(command))),
                RequestIdFilter.currentRequestId());
    }

    @PostMapping("/{memoryId}/restore")
    public ApiResponse<MemoryView> restore(
            @PathVariable String memoryId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request) {
        V1Principal principal = security.requirePermission(PermissionCode.MEMORY_MAINTAIN);
        ChangeCommand command = changeCommand(memoryId, parseIfMatch(ifMatch), "manual restore", principal, request);
        return ApiResponse.ok(
                MemoryView.from(runCommand(() -> governanceService().restore(command))),
                RequestIdFilter.currentRequestId());
    }

    @GetMapping("/extractions")
    public ListEnvelope<ExtractionView> listExtractions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String sourcePublicId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.MEMORY_READ);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 200));
        ExtractionPage result = extractionRepository()
                .list(new ExtractionQuery(status, sourceType, sourcePublicId, normalizedPage, normalizedSize));
        return ListEnvelope.of(
                result.items().stream().map(ExtractionView::from).toList(),
                normalizedPage,
                normalizedSize,
                result.total(),
                RequestIdFilter.currentRequestId());
    }

    @GetMapping("/extractions/{extractionId}")
    public ApiResponse<ExtractionView> extractionDetail(@PathVariable String extractionId) {
        security.requirePermission(PermissionCode.MEMORY_READ);
        MemoryExtractionTaskRecord extraction = extractionRepository()
                .find(extractionId)
                .orElseThrow(() -> V1ApiException.notFound("Memory extraction not found: " + extractionId));
        return ApiResponse.ok(ExtractionView.from(extraction), RequestIdFilter.currentRequestId());
    }

    @PostMapping("/extractions")
    public ResponseEntity<ApiResponse<ExtractionAccepted>> startExtraction(
            @Valid @RequestBody ExtractionRequest body, HttpServletRequest request) {
        V1Principal principal = security.requirePermission(PermissionCode.MEMORY_WRITE);
        String requestId = RequestIdFilter.currentRequestId();
        StartCommand command = new StartCommand(
                body.sourceType(),
                body.sourcePublicId(),
                body.dedupeKey(),
                body.memoryType(),
                body.scope(),
                body.subject(),
                body.content(),
                body.service(),
                body.resource(),
                body.fingerprint(),
                body.metadata(),
                null,
                null,
                0,
                principal.user() == null ? null : principal.user().id(),
                principal.user() == null ? principal.name() : principal.user().displayName(),
                Instant.now(),
                requestId,
                request.getHeader("X-Trace-Id"),
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        StartResult result = runCommand(() -> governanceService().startExtraction(command));
        ExtractionAccepted accepted = new ExtractionAccepted(
                result.extraction().publicId(),
                result.task().publicId(),
                result.task().status());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.created(accepted, requestId));
    }

    private MemoryEntryRepository memoryRepository() {
        MemoryEntryRepository repository = memoryRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw unavailable("Memory read model");
        }
        return repository;
    }

    private MemoryExtractionTaskRepository extractionRepository() {
        MemoryExtractionTaskRepository repository = extractionRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw unavailable("Memory extraction read model");
        }
        return repository;
    }

    private MemoryGovernanceService governanceService() {
        MemoryGovernanceService service = governanceServiceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            throw unavailable("Memory governance service");
        }
        return service;
    }

    private static ChangeCommand changeCommand(
            String memoryId, long expectedVersion, String reason, V1Principal principal, HttpServletRequest request) {
        return new ChangeCommand(
                memoryId,
                expectedVersion,
                reason,
                principal.user() == null ? null : principal.user().id(),
                principal.user() == null ? principal.name() : principal.user().displayName(),
                Instant.now(),
                RequestIdFilter.currentRequestId(),
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
    }

    private static <T> T runCommand(CommandCall<T> call) {
        try {
            return call.run();
        } catch (CommandException ex) {
            if (ex.failure() == MemoryGovernanceService.Failure.NOT_FOUND) {
                throw V1ApiException.notFound(ex.getMessage());
            }
            throw V1ApiException.conflict(V1ApiErrorCode.RESOURCE_VERSION_CONFLICT, ex.getMessage());
        }
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw invalid("If-Match version header is required");
        }
        try {
            long version = Long.parseLong(ifMatch.replace("\"", "").trim());
            if (version <= 0) {
                throw invalid("If-Match must be a positive numeric version");
            }
            return version;
        } catch (NumberFormatException ex) {
            throw invalid("If-Match must be a positive numeric version");
        }
    }

    private static V1ApiException unavailable(String resource) {
        return new V1ApiException(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                V1ApiErrorCode.SERVICE_UNAVAILABLE,
                resource + " is not available");
    }

    private static V1ApiException invalid(String message) {
        return new V1ApiException(HttpStatus.BAD_REQUEST.value(), V1ApiErrorCode.INVALID_REQUEST, message);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    @FunctionalInterface
    private interface CommandCall<T> {

        T run();
    }

    public record DeleteRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record ExtractionRequest(
            @NotBlank @Pattern(
                    regexp = "(?i)SESSION|EXECUTION|ALARM|MANUAL",
                    message = "sourceType must be SESSION, EXECUTION, ALARM or MANUAL")
            String sourceType,

            @NotBlank @Size(max = 128) String sourcePublicId,
            @NotBlank @Size(max = 255) String dedupeKey,

            @NotBlank @Pattern(
                    regexp = "(?i)DEVICE_HISTORY|SERVICE_FACT|KNOWN_PITFALL|INCIDENT_SUMMARY|USER_PREFERENCE|USER_NOTE",
                    message = "unsupported memoryType")
            String memoryType,

            @NotBlank @Pattern(regexp = "(?i)GLOBAL|SERVICE|RESOURCE|FINGERPRINT|SESSION", message = "unsupported memory scope")
            String scope,

            @NotBlank @Size(max = 2000) String subject,
            @NotBlank @Size(max = 100000) String content,
            @Size(max = 255) String service,
            @Size(max = 512) String resource,
            @Size(max = 512) String fingerprint,
            Map<String, String> metadata) {}

    public record ExtractionAccepted(String extractionId, String taskId, String status) {}

    public record MemoryListView(
            String id,
            String memoryType,
            String status,
            String sourceSessionId,
            String sourceExecutionId,
            String sourceAlarmId,
            java.math.BigDecimal qualityScore,
            Instant extractedAt,
            Instant expiresAt,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {

        private static MemoryListView from(MemoryEntryRecord record) {
            return new MemoryListView(
                    record.publicId(),
                    record.memoryType(),
                    record.status(),
                    record.sourceSessionId(),
                    record.sourceExecutionPublicId(),
                    record.sourceAlarmPublicId(),
                    record.qualityScore(),
                    record.extractedAt(),
                    record.expiresAt(),
                    record.version(),
                    record.createdAt(),
                    record.updatedAt(),
                    record.deletedAt());
        }
    }

    public record MemoryView(
            String id,
            String memoryType,
            String status,
            String sourceSessionId,
            String sourceExecutionId,
            String sourceAlarmId,
            Map<String, Object> evidence,
            java.math.BigDecimal qualityScore,
            String contentChecksum,
            String esIndex,
            String esDocumentId,
            Instant extractedAt,
            Instant expiresAt,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt,
            String deleteReason) {

        private static MemoryView from(MemoryEntryRecord record) {
            return new MemoryView(
                    record.publicId(),
                    record.memoryType(),
                    record.status(),
                    record.sourceSessionId(),
                    record.sourceExecutionPublicId(),
                    record.sourceAlarmPublicId(),
                    record.evidence(),
                    record.qualityScore(),
                    record.contentChecksum(),
                    record.esIndex(),
                    record.esDocumentId(),
                    record.extractedAt(),
                    record.expiresAt(),
                    record.version(),
                    record.createdAt(),
                    record.updatedAt(),
                    record.deletedAt(),
                    record.deleteReason());
        }
    }

    public record ExtractionView(
            String id,
            String taskId,
            String sourceType,
            String sourcePublicId,
            String dedupeKey,
            String status,
            String extractorModel,
            String extractorVersion,
            int evidenceCount,
            int memoryCount,
            Map<String, Object> qualitySummary,
            String errorCode,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        private static ExtractionView from(MemoryExtractionTaskRecord record) {
            return new ExtractionView(
                    record.publicId(),
                    record.taskPublicId(),
                    record.sourceType(),
                    record.sourcePublicId(),
                    record.dedupeKey(),
                    record.status(),
                    record.extractorModel(),
                    record.extractorVersion(),
                    record.evidenceCount(),
                    record.memoryCount(),
                    record.qualitySummary(),
                    record.errorCode(),
                    record.errorSummary(),
                    record.startedAt(),
                    record.finishedAt(),
                    record.version(),
                    record.createdAt(),
                    record.updatedAt());
        }
    }
}
