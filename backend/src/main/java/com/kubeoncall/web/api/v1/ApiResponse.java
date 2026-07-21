package com.kubeoncall.web.api.v1;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Unified {@code /api/v1} success envelope: {@code {data, meta}}. List responses use the array
 * variant together with {@link PageMeta}. Error responses are produced by {@link ApiErrorEnvelope}
 * via {@code ApiExceptionHandler} and never reuse this class.
 */
public record ApiResponse<T>(T data, ResponseMeta meta) {

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(data, ResponseMeta.now(requestId));
    }

    public static <T> ApiResponse<T> created(T data, String requestId) {
        return new ApiResponse<>(data, ResponseMeta.now(requestId));
    }

    /**
     * Per-request metadata shared by every success and error response. {@code requestId} is the
     * authoritative correlation id echoed to the client and written to logs via MDC.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResponseMeta(String requestId, Instant timestamp) {

        public static ResponseMeta now(String requestId) {
            return new ResponseMeta(requestId, Instant.now());
        }
    }
}
