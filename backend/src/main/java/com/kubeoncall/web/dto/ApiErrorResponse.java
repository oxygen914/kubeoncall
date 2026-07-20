package com.kubeoncall.web.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable JSON response body for failed API requests. */
public record ApiErrorResponse(
        Instant timestamp, int status, ApiErrorCode code, String message, String path, Map<String, Object> details) {

    public ApiErrorResponse {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        code = code == null ? ApiErrorCode.INTERNAL_ERROR : code;
        message = message == null || message.isBlank() ? "Request failed" : message;
        path = path == null ? "" : path;
        details = Map.copyOf(new LinkedHashMap<>(details == null ? Map.of() : details));
    }
}
