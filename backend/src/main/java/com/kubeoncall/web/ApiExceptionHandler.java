package com.kubeoncall.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.kubeoncall.alarm.inbox.AlarmInboxUnavailableException;
import com.kubeoncall.alarm.ingest.AlarmIngestionRejectedException;
import com.kubeoncall.alarm.integration.alertmanager.WebhookAuthenticationException;
import com.kubeoncall.alarm.integration.alertmanager.WebhookPayloadTooLargeException;
import com.kubeoncall.common.exception.ApprovalRequiredException;
import com.kubeoncall.common.exception.KubeOnCallException;
import com.kubeoncall.common.exception.ReplanRequiredException;
import com.kubeoncall.web.dto.ApiErrorCode;
import com.kubeoncall.web.dto.ApiErrorResponse;

/** Maps application and transport failures to the stable API error contract. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApprovalRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleApprovalRequired(
            ApprovalRequiredException exception, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (exception.getExecutionId() != null && !exception.getExecutionId().isBlank()) {
            details.put("executionId", exception.getExecutionId());
        }
        return response(HttpStatus.CONFLICT, ApiErrorCode.APPROVAL_REQUIRED, exception.getMessage(), request, details);
    }

    @ExceptionHandler(ReplanRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleReplanRequired(
            ReplanRequiredException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ApiErrorCode.REPLAN_REQUIRED, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(KubeOnCallException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessError(
            KubeOnCallException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ApiErrorCode.BUSINESS_ERROR, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            IllegalArgumentException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(WebhookAuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleWebhookAuthentication(
            WebhookAuthenticationException exception, HttpServletRequest request) {
        HttpStatus status =
                exception.getMessage() != null && exception.getMessage().contains("authentication failed")
                        ? HttpStatus.UNAUTHORIZED
                        : HttpStatus.SERVICE_UNAVAILABLE;
        return response(status, ApiErrorCode.BUSINESS_ERROR, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(WebhookPayloadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handleWebhookTooLarge(
            WebhookPayloadTooLargeException exception, HttpServletRequest request) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE, ApiErrorCode.BUSINESS_ERROR, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler({AlarmInboxUnavailableException.class, AlarmIngestionRejectedException.class})
    public ResponseEntity<ApiErrorResponse> handleAlarmInboxFailure(
            RuntimeException exception, HttpServletRequest request) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.BUSINESS_ERROR, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, ApiErrorCode.MALFORMED_REQUEST, "Request body is malformed", request, Map.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            IllegalStateException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception, HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        return response(
                status,
                statusCode(status),
                exception.getReason() == null ? status.toString() : exception.getReason(),
                request,
                Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error(
                "Unhandled API exception: path={}, errorType={}",
                request.getRequestURI(),
                exception.getClass().getSimpleName());
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_ERROR,
                "Internal server error",
                request,
                Map.of());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status,
            ApiErrorCode code,
            String message,
            HttpServletRequest request,
            Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(
                        Instant.now(), status.value(), code, message, request.getRequestURI(), details));
    }

    private ApiErrorCode statusCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ApiErrorCode.INVALID_REQUEST;
            case 404 -> ApiErrorCode.NOT_FOUND;
            case 409 -> ApiErrorCode.CONFLICT;
            default -> ApiErrorCode.BUSINESS_ERROR;
        };
    }
}
