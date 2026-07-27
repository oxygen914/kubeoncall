package com.kubeoncall.web.api.v1.sandbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.sandbox.SandboxArtifactRecord;
import com.kubeoncall.sandbox.SandboxRunCommandException;
import com.kubeoncall.sandbox.SandboxRunCommandService;
import com.kubeoncall.sandbox.SandboxRunRecord;
import com.kubeoncall.sandbox.SandboxRunRepository;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * V1 control-plane API for durable sandbox runs.
 *
 * <p>Requests create or cancel only the database fact and its outbox event. They deliberately do
 * not contact the isolated runtime; dispatch and eventual convergence are asynchronous SBX-12/13
 * responsibilities.
 */
@RestController
@RequestMapping("/api/v1/sandbox-runs")
public class SandboxRunsController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final String CREATE_ROUTE = "POST:/api/v1/sandbox-runs";

    private final ObjectProvider<SandboxRunCommandService> commandServiceProvider;
    private final ObjectProvider<SandboxRunRepository> repositoryProvider;
    private final V1Security security;

    public SandboxRunsController(
            ObjectProvider<SandboxRunCommandService> commandServiceProvider,
            ObjectProvider<SandboxRunRepository> repositoryProvider,
            V1Security security) {
        this.commandServiceProvider = commandServiceProvider;
        this.repositoryProvider = repositoryProvider;
        this.security = security;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CreateSandboxRunRequest body,
            HttpServletRequest request) {
        V1Principal actor = requireUser(PermissionCode.SANDBOX_EXECUTE, "create a sandbox run");
        if (body == null) {
            throw invalid("Request body is required");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        SandboxRunCommandService.CommandResult result;
        try {
            result = commandService()
                    .create(
                            new SandboxRunCommandService.CreateCommand(
                                    body.mode(),
                                    body.toolId(),
                                    body.toolVersion(),
                                    body.executionId(),
                                    body.alarmId(),
                                    body.generatedCodeAutoRun(),
                                    body.approvalWaiverRequested(),
                                    body.expiresAt(),
                                    actor.user().id(),
                                    actor.user().publicId(),
                                    actor.user().displayName(),
                                    RequestIdFilter.currentRequestId(),
                                    request.getHeader("X-Trace-Id"),
                                    clientIp(request),
                                    request.getHeader(HttpHeaders.USER_AGENT)),
                            new IdempotencyService.IdempotencyScope(
                                    "USER", actor.user().publicId(), CREATE_ROUTE),
                            key);
        } catch (SandboxRunCommandException ex) {
            throw map(ex);
        }
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.ok(result.data(), RequestIdFilter.currentRequestId()));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(name = "page", defaultValue = "1") int requestedPage,
            @RequestParam(name = "size", defaultValue = "20") int requestedSize,
            @RequestParam(name = "mode", required = false) SandboxRunMode mode,
            @RequestParam(name = "status", required = false) SandboxRunStatus status,
            @RequestParam(name = "executionId", required = false) String executionId,
            @RequestParam(name = "alarmId", required = false) String alarmId) {
        security.requirePermission(PermissionCode.SANDBOX_READ);
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        List<Map<String, Object>> runs = repository()
                .list(
                        new SandboxRunRepository.RunQuery(mode, status, executionId, alarmId, null, null),
                        (page - 1) * size,
                        size)
                .stream()
                .map(run -> SandboxRunCommandService.view(run, false))
                .toList();
        return ApiResponse.ok(runs, RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{runId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String runId) {
        security.requirePermission(PermissionCode.SANDBOX_READ);
        SandboxRunRecord run = repository().findByPublicId(runId).orElseThrow(() -> notFound(runId));
        return ApiResponse.ok(SandboxRunCommandService.view(run, false), RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{runId}/artifacts")
    public ApiResponse<List<ArtifactMetadataView>> artifacts(@PathVariable String runId) {
        security.requirePermission(PermissionCode.SANDBOX_READ);
        SandboxRunRecord run = repository().findByPublicId(runId).orElseThrow(() -> notFound(runId));
        List<ArtifactMetadataView> artifacts = repository().findArtifactsByRun(run.id()).stream()
                .map(ArtifactMetadataView::from)
                .toList();
        return ApiResponse.ok(artifacts, RequestIdFilter.currentRequestId());
    }

    @PostMapping("/{runId}/cancel")
    public ApiResponse<Map<String, Object>> cancel(
            @PathVariable String runId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request) {
        V1Principal actor = requireUser(PermissionCode.SANDBOX_CANCEL, "cancel a sandbox run");
        try {
            Map<String, Object> response = commandService()
                    .cancel(new SandboxRunCommandService.CancelCommand(
                            runId,
                            requiredVersion(ifMatch),
                            Instant.now(),
                            actor.user().id(),
                            actor.user().displayName(),
                            RequestIdFilter.currentRequestId(),
                            request.getHeader("X-Trace-Id"),
                            clientIp(request),
                            request.getHeader(HttpHeaders.USER_AGENT)));
            return ApiResponse.ok(response, RequestIdFilter.currentRequestId());
        } catch (SandboxRunCommandException ex) {
            throw map(ex);
        }
    }

    private SandboxRunCommandService commandService() {
        SandboxRunCommandService service = commandServiceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Sandbox command service is not available");
        }
        return service;
    }

    private SandboxRunRepository repository() {
        SandboxRunRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Sandbox read model is not available");
        }
        return repository;
    }

    private V1Principal requireUser(String permission, String action) {
        V1Principal principal = security.requirePermission(permission);
        if (principal.user() == null || principal.user().id() <= 0) {
            throw new V1ApiException(
                    HttpStatus.FORBIDDEN.value(),
                    V1ApiErrorCode.FORBIDDEN,
                    "A user-backed session is required to " + action);
        }
        return principal;
    }

    private static long requiredVersion(String ifMatch) {
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

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw invalid("Idempotency-Key must contain 16 to 128 characters");
        }
        return idempotencyKey;
    }

    private static V1ApiException map(SandboxRunCommandException exception) {
        return switch (exception.code()) {
            case INVALID -> invalid(exception.getMessage());
            case NOT_FOUND -> notFound(exception.getMessage());
            case VERSION_CONFLICT ->
                new V1ApiException(
                        HttpStatus.CONFLICT.value(), V1ApiErrorCode.RESOURCE_VERSION_CONFLICT, exception.getMessage());
            case SERVICE_UNAVAILABLE ->
                new V1ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        V1ApiErrorCode.SERVICE_UNAVAILABLE,
                        exception.getMessage());
            case IDEMPOTENCY_IN_PROGRESS ->
                new V1ApiException(
                        HttpStatus.CONFLICT.value(),
                        V1ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        exception.getMessage());
            case IDEMPOTENCY_REUSED ->
                new V1ApiException(
                        HttpStatus.CONFLICT.value(), V1ApiErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getMessage());
        };
    }

    private static V1ApiException invalid(String message) {
        return new V1ApiException(HttpStatus.BAD_REQUEST.value(), V1ApiErrorCode.INVALID_REQUEST, message);
    }

    private static V1ApiException notFound(String runId) {
        return V1ApiException.notFound("Sandbox run not found: " + runId);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    public record CreateSandboxRunRequest(
            SandboxRunMode mode,
            @NotBlank @Size(max = 128) String toolId,
            @NotBlank @Size(max = 64) String toolVersion,
            @Size(max = 40) String executionId,
            @Size(max = 40) String alarmId,
            boolean generatedCodeAutoRun,
            boolean approvalWaiverRequested,
            Instant expiresAt) {}

    /** Metadata only: bucket/object key and any artifact contents are never exposed by this API. */
    public record ArtifactMetadataView(
            String id,
            String type,
            String contentType,
            long sizeBytes,
            String sha256,
            String classification,
            Instant retentionUntil,
            Instant createdAt) {

        private static ArtifactMetadataView from(SandboxArtifactRecord artifact) {
            return new ArtifactMetadataView(
                    artifact.publicId(),
                    artifact.artifactType().name(),
                    artifact.contentType(),
                    artifact.sizeBytes(),
                    artifact.sha256(),
                    artifact.classification().name(),
                    artifact.retentionUntil(),
                    artifact.createdAt());
        }
    }
}
