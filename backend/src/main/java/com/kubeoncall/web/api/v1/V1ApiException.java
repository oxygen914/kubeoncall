package com.kubeoncall.web.api.v1;

import java.util.List;
import java.util.Map;

/**
 * Domain exception mapped by {@link V1ApiExceptionHandler} to the unified {@code /api/v1} error
 * envelope. Carries an HTTP status, a stable {@link V1ApiErrorCode} and optional structured details
 * that are safe to expose to the client. Field validation errors are attached separately so the UI
 * can annotate inputs without parsing free-text messages.
 */
public class V1ApiException extends RuntimeException {

    private final int status;
    private final V1ApiErrorCode code;
    private final boolean retryable;
    private final List<ApiErrorEnvelope.FieldError> fieldErrors;
    private final transient Map<String, Object> details;

    public V1ApiException(int status, V1ApiErrorCode code, String message) {
        this(status, code, message, code.isRetryable(), List.of(), Map.of());
    }

    public V1ApiException(
            int status,
            V1ApiErrorCode code,
            String message,
            boolean retryable,
            List<ApiErrorEnvelope.FieldError> fieldErrors,
            Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static V1ApiException of(int status, V1ApiErrorCode code, String message) {
        return new V1ApiException(status, code, message);
    }

    public static V1ApiException unauthenticated(String message) {
        return new V1ApiException(401, V1ApiErrorCode.UNAUTHENTICATED, message);
    }

    public static V1ApiException forbidden(String message) {
        return new V1ApiException(403, V1ApiErrorCode.FORBIDDEN, message);
    }

    public static V1ApiException notFound(String message) {
        return new V1ApiException(404, V1ApiErrorCode.NOT_FOUND, message);
    }

    public static V1ApiException conflict(V1ApiErrorCode code, String message) {
        return new V1ApiException(409, code, message);
    }

    public int status() {
        return status;
    }

    public V1ApiErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public List<ApiErrorEnvelope.FieldError> fieldErrors() {
        return fieldErrors;
    }

    public Map<String, Object> details() {
        return details;
    }
}
