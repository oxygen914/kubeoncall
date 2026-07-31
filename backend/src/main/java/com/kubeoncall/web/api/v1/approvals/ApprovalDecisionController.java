package com.kubeoncall.web.api.v1.approvals;

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
import org.springframework.web.bind.annotation.PathVariable;
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
import com.kubeoncall.workflow.runtime.ApprovalDecisionCommandService;
import com.kubeoncall.workflow.runtime.AsyncCommandResult;

/** Records one optimistic-lock approval decision and returns the durable resume task. */
@RestController
@RequestMapping("/api/v1/approvals/{approvalId}")
public class ApprovalDecisionController {

    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    private final ObjectProvider<ApprovalDecisionCommandService> serviceProvider;
    private final V1Security security;

    public ApprovalDecisionController(
            ObjectProvider<ApprovalDecisionCommandService> serviceProvider, V1Security security) {
        this.serviceProvider = serviceProvider;
        this.security = security;
    }

    @PostMapping("/decisions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(
            @PathVariable String approvalId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) ApprovalDecisionRequest body,
            HttpServletRequest request) {
        V1Principal principal = requireUser();
        ApprovalDecisionCommandService service = requiredService();
        if (body == null) {
            throw invalid("Request body is required");
        }
        long version = parseIfMatch(ifMatch);
        String key = requireIdempotencyKey(idempotencyKey);
        String requestId = RequestIdFilter.currentRequestId();
        ApprovalDecisionCommandService.DecisionCommand command = new ApprovalDecisionCommandService.DecisionCommand(
                approvalId,
                version,
                body.decision(),
                body.comment(),
                principal.user().id(),
                principal.user().publicId(),
                principal.user().displayName(),
                Instant.now(),
                requestId,
                request.getHeader("X-Trace-Id"),
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        String route = "POST:/api/v1/approvals/" + approvalId + "/decisions";
        IdempotencyService.IdempotencyScope scope =
                new IdempotencyService.IdempotencyScope("USER", principal.user().publicId(), route);
        AsyncCommandResult result = service.decide(command, scope, key, canonical(approvalId, version, body));
        return response(result);
    }

    private V1Principal requireUser() {
        V1Principal principal = security.requirePermission(PermissionCode.APPROVAL_DECIDE);
        if (principal.user() == null || principal.user().id() <= 0) {
            throw new V1ApiException(
                    HttpStatus.FORBIDDEN.value(),
                    V1ApiErrorCode.FORBIDDEN,
                    "A user-backed session is required to decide an approval");
        }
        return principal;
    }

    private ApprovalDecisionCommandService requiredService() {
        ApprovalDecisionCommandService service = serviceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Approval command service is not available");
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

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw invalid("Idempotency-Key must contain 16 to 128 characters");
        }
        return idempotencyKey;
    }

    private static String canonical(String approvalId, long version, ApprovalDecisionRequest body) {
        return "approval|"
                + approvalId
                + "|"
                + version
                + "|"
                + body.decision().trim().toUpperCase()
                + "|"
                + (body.comment() == null ? "" : body.comment());
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

    public record ApprovalDecisionRequest(
            @NotBlank @Pattern(regexp = "(?i)APPROVED|REJECTED", message = "decision must be APPROVED or REJECTED")
            String decision,

            @Size(max = 2000) String comment) {}
}
