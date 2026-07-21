package com.kubeoncall.web.api.v1.alarms;

import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.readmodel.AlarmCommandService;
import com.kubeoncall.alarm.readmodel.AlarmCommandService.CommandExecution;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * Versioned alarm command endpoints. Actor identity is always derived from the authenticated user;
 * every command requires optimistic locking and a client idempotency key. The service commits the
 * idempotency record, business state, history, audit and outbox atomically.
 */
@RestController
@RequestMapping("/api/v1/alarms/{alarmId}")
public class AlarmCommandsController {

    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    private final AlarmCommandService commandService;
    private final V1Security security;

    public AlarmCommandsController(AlarmCommandService commandService, V1Security security) {
        this.commandService = commandService;
        this.security = security;
    }

    @PostMapping("/acknowledgements")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acknowledge(
            @PathVariable String alarmId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) AcknowledgeRequest body,
            HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.ALARM_ACKNOWLEDGE);
        ensureAvailable();
        requireBody(body);
        long version = parseIfMatch(ifMatch);
        String key = requireIdempotencyKey(idempotencyKey);
        Instant now = Instant.now();
        String requestId = RequestIdFilter.currentRequestId();
        AlarmCommandService.AcknowledgeCommand command = new AlarmCommandService.AcknowledgeCommand(
                alarmId,
                version,
                principal.user().id(),
                "USER",
                principal.user().displayName(),
                body.reason(),
                now,
                body.expiresAt(),
                requestId,
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        CommandExecution execution = commandService.executeAcknowledge(
                command,
                scope(principal, route(alarmId, "acknowledgements")),
                key,
                canonical("ack", alarmId, version, body.reason(), body.expiresAt()));
        return response(execution);
    }

    @PostMapping("/recovery-confirmations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmRecovery(
            @PathVariable String alarmId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) RecoveryConfirmationRequest body,
            HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.ALARM_RECOVER);
        ensureAvailable();
        requireBody(body);
        long version = parseIfMatch(ifMatch);
        String key = requireIdempotencyKey(idempotencyKey);
        Instant now = Instant.now();
        String requestId = RequestIdFilter.currentRequestId();
        AlarmCommandService.RecoveryConfirmationCommand command = new AlarmCommandService.RecoveryConfirmationCommand(
                alarmId,
                version,
                principal.user().id(),
                "USER",
                principal.user().displayName(),
                Boolean.TRUE.equals(body.healthCheckPassed()),
                body.note(),
                now,
                requestId,
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        CommandExecution execution = commandService.executeRecoveryConfirmation(
                command,
                scope(principal, route(alarmId, "recovery-confirmations")),
                key,
                canonical("recovery", alarmId, version, String.valueOf(body.healthCheckPassed()), body.note()));
        return response(execution);
    }

    @PostMapping("/silence-approvals")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveSilence(
            @PathVariable String alarmId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) SilenceApprovalRequest body,
            HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.ALARM_SILENCE);
        ensureAvailable();
        requireBody(body);
        long version = parseIfMatch(ifMatch);
        String key = requireIdempotencyKey(idempotencyKey);
        Instant now = Instant.now();
        String requestId = RequestIdFilter.currentRequestId();
        AlarmCommandService.SilenceApprovalCommand command = new AlarmCommandService.SilenceApprovalCommand(
                alarmId,
                version,
                principal.user().id(),
                "USER",
                principal.user().displayName(),
                body.reason(),
                now,
                body.expiresAt(),
                requestId,
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        CommandExecution execution = commandService.executeSilenceApproval(
                command,
                scope(principal, route(alarmId, "silence-approvals")),
                key,
                canonical("silence", alarmId, version, body.reason(), body.expiresAt()));
        return response(execution);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> response(CommandExecution execution) {
        return switch (execution.action()) {
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
            case EXECUTED, REPLAY -> {
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(execution.httpStatus());
                if (execution.version() != null) {
                    builder.eTag(String.valueOf(execution.version()));
                }
                yield builder.body(ApiResponse.ok(execution.data(), RequestIdFilter.currentRequestId()));
            }
        };
    }

    private V1Principal requireUser(String permission) {
        V1Principal principal = security.requirePermission(permission);
        if (principal.user() == null || principal.user().id() <= 0) {
            throw new V1ApiException(
                    HttpStatus.FORBIDDEN.value(),
                    V1ApiErrorCode.FORBIDDEN,
                    "A user-backed session is required for this command");
        }
        return principal;
    }

    private void ensureAvailable() {
        if (!commandService.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Alarm command service is not available");
        }
    }

    private static IdempotencyService.IdempotencyScope scope(V1Principal principal, String route) {
        return new IdempotencyService.IdempotencyScope("USER", principal.user().publicId(), route);
    }

    private static String route(String alarmId, String action) {
        return "POST:/api/v1/alarms/" + alarmId + "/" + action;
    }

    private static String canonical(String action, String alarmId, long version, Object... values) {
        StringBuilder canonical = new StringBuilder(action)
                .append('|')
                .append(alarmId)
                .append('|')
                .append(version);
        for (Object value : values) {
            canonical.append('|').append(value == null ? "" : value);
        }
        return canonical.toString();
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw invalid("If-Match version header is required");
        }
        String trimmed = ifMatch.replace("\"", "").trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw invalid("If-Match must be a numeric version");
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

    private static void requireBody(Object body) {
        if (body == null) {
            throw invalid("Request body is required");
        }
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

    public record AcknowledgeRequest(@Size(max = 1000) String reason, Instant expiresAt) {}

    public record RecoveryConfirmationRequest(
            @NotNull Boolean healthCheckPassed,
            @Size(max = 1000) String note) {}

    public record SilenceApprovalRequest(
            @Size(max = 1000) String reason, @NotNull Instant expiresAt) {}
}
