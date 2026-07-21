package com.kubeoncall.web.api.v1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Unified {@code /api/v1} error envelope: {@code {error, meta}}. Codes are stable, machine-readable
 * identifiers the frontend maps to UX; {@code message} is safe to display but must not be used for
 * control flow. Field validation errors surface per-field so the UI can mark inputs.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"error", "meta"})
public record ApiErrorEnvelope(ApiErrorBody error, ApiResponse.ResponseMeta meta) {

    public static ApiErrorEnvelope of(
            String code,
            String message,
            boolean retryable,
            List<FieldError> fieldErrors,
            Map<String, Object> details,
            String requestId) {
        return new ApiErrorEnvelope(
                new ApiErrorBody(code, message, retryable, fieldErrors, details),
                ApiResponse.ResponseMeta.now(requestId));
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"code", "message", "retryable", "fieldErrors", "details"})
    public record ApiErrorBody(
            String code, String message, boolean retryable, List<FieldError> fieldErrors, Map<String, Object> details) {

        public ApiErrorBody {
            fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
            details = Map.copyOf(new LinkedHashMap<>(details == null ? Map.of() : details));
        }
    }

    public record FieldError(String field, String code, String message) {}
}
