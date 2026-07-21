package com.kubeoncall.observability;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;

/** Stable MDC field names and a scoped worker context that cannot leak across pooled threads. */
public final class CorrelationContext {

    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String AUTH_METHOD = "authMethod";
    public static final String ROUTE = "route";
    public static final String TASK_ID = "taskId";
    public static final String TASK_TYPE = "taskType";
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";

    private static final int MAX_MDC_VALUE_LENGTH = 256;

    private CorrelationContext() {}

    /**
     * Replaces the current MDC for one worker operation and restores the caller's context on close.
     * Null/blank values are omitted and control characters are removed before entering logs.
     */
    public static Scope open(Map<String, ?> values) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.clear();
        if (values != null) {
            values.forEach((key, value) -> put(key, value));
        }
        return new Scope(previous);
    }

    public static void put(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        String safe = sanitize(String.valueOf(value));
        if (!safe.isBlank()) {
            MDC.put(key, safe);
        }
    }

    public static void remove(String... keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            if (key != null) {
                MDC.remove(key);
            }
        }
    }

    private static String sanitize(String value) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_MDC_VALUE_LENGTH));
        for (int index = 0; index < value.length() && safe.length() < MAX_MDC_VALUE_LENGTH; index++) {
            char current = value.charAt(index);
            if (current >= 0x20 && current != 0x7f) {
                safe.append(current);
            }
        }
        return safe.toString().trim();
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous;
        private boolean closed;

        private Scope(Map<String, String> previous) {
            this.previous = previous == null ? Map.of() : new LinkedHashMap<>(previous);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MDC.clear();
            if (!previous.isEmpty()) {
                MDC.setContextMap(previous);
            }
        }
    }
}
