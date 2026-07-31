package com.kubeoncall.task.worker;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable task-type registry. Duplicate task types fail fast during construction. */
public final class AsyncTaskHandlerRegistry {

    private final Map<String, AsyncTaskHandler> handlers;

    public AsyncTaskHandlerRegistry(Collection<? extends AsyncTaskHandler> handlers) {
        Map<String, AsyncTaskHandler> indexed = new LinkedHashMap<>();
        if (handlers != null) {
            for (AsyncTaskHandler handler : handlers) {
                if (handler == null) {
                    throw new IllegalArgumentException("Async task handler must not be null");
                }
                String taskType = requireText(handler.taskType(), "handler taskType");
                AsyncTaskHandler previous = indexed.putIfAbsent(taskType, handler);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate async task handler for task type: " + taskType);
                }
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    public Optional<AsyncTaskHandler> find(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(taskType.trim()));
    }

    public int size() {
        return handlers.size();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
