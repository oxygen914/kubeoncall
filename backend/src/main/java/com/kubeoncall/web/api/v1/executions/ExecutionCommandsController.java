package com.kubeoncall.web.api.v1.executions;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;
import com.kubeoncall.workflow.runtime.AsyncCommandResult;
import com.kubeoncall.workflow.runtime.WorkflowSubmissionService;

/** Starts an Ask workflow by creating a durable execution and task; execution remains asynchronous. */
@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionCommandsController {

    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final String ROUTE = "POST:/api/v1/executions";

    private final ObjectProvider<WorkflowSubmissionService> serviceProvider;
    private final V1Security security;

    public ExecutionCommandsController(ObjectProvider<WorkflowSubmissionService> serviceProvider, V1Security security) {
        this.serviceProvider = serviceProvider;
        this.security = security;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CreateExecutionRequest body,
            HttpServletRequest request) {
        V1Principal principal = requireUser();
        WorkflowSubmissionService service = requiredService();
        if (body == null) {
            throw invalid("Request body is required");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        String requestId = RequestIdFilter.currentRequestId();
        WorkflowSubmissionService.SubmitAskCommand command = new WorkflowSubmissionService.SubmitAskCommand(
                body.question(),
                body.sessionId(),
                body.alarmId(),
                principal.user().id(),
                principal.user().publicId(),
                principal.user().displayName(),
                requestId,
                request.getHeader("X-Trace-Id"),
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        IdempotencyService.IdempotencyScope scope =
                new IdempotencyService.IdempotencyScope("USER", principal.user().publicId(), ROUTE);
        AsyncCommandResult result = service.submitAsk(command, scope, key, canonical(body));
        return response(result);
    }

    private V1Principal requireUser() {
        V1Principal principal = security.requirePermission(PermissionCode.ASK_EXECUTE);
        if (principal.user() == null || principal.user().id() <= 0) {
            throw new V1ApiException(
                    HttpStatus.FORBIDDEN.value(),
                    V1ApiErrorCode.FORBIDDEN,
                    "A user-backed session is required to create an execution");
        }
        return principal;
    }

    private WorkflowSubmissionService requiredService() {
        WorkflowSubmissionService service = serviceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Workflow command service is not available");
        }
        return service;
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> response(AsyncCommandResult result) {
        return switch (result.action()) {
            case IN_PROGRESS ->
                throw new V1ApiException(
                        HttpStatus.CONFLICT.value(),
                        V1ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "An idempotent request for this key is already in progress");
            case REUSED ->
                throw new V1ApiException(
                        HttpStatus.CONFLICT.value(),
                        V1ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was used with a different request");
            case EXECUTED, REPLAY ->
                ResponseEntity.status(result.httpStatus())
                        .body(ApiResponse.ok(result.data(), RequestIdFilter.currentRequestId()));
        };
    }

    private static String canonical(CreateExecutionRequest body) {
        return "ask|" + body.question().trim() + "|" + safe(body.sessionId()) + "|" + safe(body.alarmId());
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw invalid("Idempotency-Key must contain 16 to 128 characters");
        }
        return idempotencyKey;
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record CreateExecutionRequest(
            @NotBlank @Size(max = 8000) String question,
            @Size(max = 128) String sessionId,
            @Size(max = 40) String alarmId) {}
}
