package com.kubeoncall.web.api.v1;

import java.util.List;

/**
 * Pagination metadata for list responses. {@code number} is 1-based to match the API contract
 * ({@code page} query param starts at 1).
 */
public record PageMeta(int number, int size, long totalElements, int totalPages, boolean hasNext) {

    public static PageMeta of(int number, int size, long totalElements) {
        int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) totalElements / size);
        if (totalPages < 1) {
            totalPages = 1;
        }
        boolean hasNext = number < totalPages;
        return new PageMeta(number, size, totalElements, totalPages, hasNext);
    }

    /** Convenience list envelope pairing {@code data} with {@code page} and {@code meta}. */
    public record ListEnvelope<T>(List<T> data, PageMeta page, ApiResponse.ResponseMeta meta) {

        public static <T> ListEnvelope<T> of(List<T> data, int number, int size, long totalElements, String requestId) {
            return new ListEnvelope<>(
                    data, PageMeta.of(number, size, totalElements), ApiResponse.ResponseMeta.now(requestId));
        }
    }
}
