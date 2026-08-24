package com.kubeoncall.web.api.v1;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.kubeoncall.alarm.readmodel.AlarmCommandException;
import com.kubeoncall.workflow.runtime.WorkflowCommandException;

/**
 * Maps failures raised inside the {@code com.kubeoncall.web.api.v1} package to the unified
 * {@code {error, meta}} envelope. Scoped to the v1 package so the legacy {@code /api} surface keeps
 * its existing {@code ApiErrorResponse} contract untouched during the migration window.
 */
@RestControllerAdvice(basePackages = "com.kubeoncall.web.api.v1")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class V1ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(V1ApiExceptionHandler.class);

    @ExceptionHandler(AlarmCommandException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAlarmCommand(AlarmCommandException ex, HttpServletRequest request) {
        V1ApiErrorCode code = mapAlarmCommandCode(ex.code());
        return ResponseEntity.status(ex.code().httpStatus())
                .body(ApiErrorEnvelope.of(
                        code.name(), message(ex), code.isRetryable(), List.of(), Map.of(), requestId()));
    }

    private static V1ApiErrorCode mapAlarmCommandCode(AlarmCommandException.Code code) {
        return switch (code) {
            case NOT_FOUND -> V1ApiErrorCode.NOT_FOUND;
            case CONFLICT -> V1ApiErrorCode.CONFLICT;
            case RESOURCE_VERSION_CONFLICT -> V1ApiErrorCode.RESOURCE_VERSION_CONFLICT;
            case SERVICE_UNAVAILABLE -> V1ApiErrorCode.SERVICE_UNAVAILABLE;
            case INVALID -> V1ApiErrorCode.INVALID_REQUEST;
        };
    }

    @ExceptionHandler(WorkflowCommandException.class)
    public ResponseEntity<ApiErrorEnvelope> handleWorkflowCommand(
            WorkflowCommandException ex, HttpServletRequest request) {
        V1ApiErrorCode code =
                switch (ex.code()) {
                    case INVALID -> V1ApiErrorCode.INVALID_REQUEST;
                    case NOT_FOUND -> V1ApiErrorCode.NOT_FOUND;
                    case CONFLICT -> V1ApiErrorCode.CONFLICT;
                    case RESOURCE_VERSION_CONFLICT -> V1ApiErrorCode.RESOURCE_VERSION_CONFLICT;
                    case SERVICE_UNAVAILABLE -> V1ApiErrorCode.SERVICE_UNAVAILABLE;
                };
        int status =
                switch (ex.code()) {
                    case INVALID -> HttpStatus.BAD_REQUEST.value();
                    case NOT_FOUND -> HttpStatus.NOT_FOUND.value();
                    case CONFLICT, RESOURCE_VERSION_CONFLICT -> HttpStatus.CONFLICT.value();
                    case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE.value();
                };
        return ResponseEntity.status(status)
                .body(ApiErrorEnvelope.of(
                        code.name(), message(ex), code.isRetryable(), List.of(), Map.of(), requestId()));
    }

    @ExceptionHandler(V1ApiException.class)
    public ResponseEntity<ApiErrorEnvelope> handleV1(V1ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status())
                .body(ApiErrorEnvelope.of(
                        ex.code().name(), message(ex), ex.retryable(), ex.fieldErrors(), ex.details(), requestId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiErrorEnvelope.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiErrorEnvelope.FieldError(
                        f.getField(),
                        resolveFieldCode(f),
                        f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiErrorEnvelope.of(
                        V1ApiErrorCode.VALIDATION_FAILED.name(),
                        "Request validation failed",
                        false,
                        fields,
                        Map.of(),
                        requestId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorEnvelope> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorEnvelope.of(
                        V1ApiErrorCode.INVALID_REQUEST.name(),
                        "Request body is malformed or contains unsupported fields",
                        false,
                        List.of(),
                        Map.of(),
                        requestId()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorEnvelope.of(
                        V1ApiErrorCode.UNAUTHENTICATED.name(),
                        "Authentication required",
                        false,
                        List.of(),
                        Map.of(),
                        requestId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorEnvelope.of(
                        V1ApiErrorCode.FORBIDDEN.name(),
                        "Insufficient permissions",
                        false,
                        List.of(),
                        Map.of(),
                        requestId()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorEnvelope.of(
                        V1ApiErrorCode.NOT_FOUND.name(),
                        "Resource not found",
                        false,
                        List.of(),
                        Map.of(),
                        requestId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorEnvelope> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorEnvelope.of(
                        V1ApiErrorCode.INVALID_REQUEST.name(), message(ex), false, List.of(), Map.of(), requestId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorEnvelope> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error(
                "Unhandled v1 API exception: path={}, requestId={}, errorType={}",
                request.getRequestURI(),
                requestId(),
                ex.getClass().getSimpleName(),
                ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorEnvelope.of(
                        V1ApiErrorCode.INTERNAL_ERROR.name(),
                        "Internal server error",
                        V1ApiErrorCode.INTERNAL_ERROR.isRetryable(),
                        List.of(),
                        Map.of(),
                        requestId()));
    }

    private static String message(Throwable ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Request failed" : ex.getMessage();
    }

    private static String resolveFieldCode(org.springframework.validation.FieldError field) {
        return field.getCode() == null ? "INVALID" : field.getCode().toUpperCase();
    }

    private static String requestId() {
        return RequestIdFilter.currentRequestId();
    }
}
