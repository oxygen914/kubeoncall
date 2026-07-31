package com.kubeoncall.task.worker;

import java.util.Map;

/**
 * Executes one durable async task.
 *
 * <p>Handlers may call external systems. They are therefore invoked after the repository claim
 * transaction has committed. Implementations must make externally visible side effects idempotent
 * using the task public id or another stable business key.
 */
public interface AsyncTaskHandler {

    String taskType();

    HandlerResult handle(AsyncTaskContext context) throws Exception;

    /** The worker alone owns the durable task transition; handlers only return its result payload. */
    record HandlerResult(Map<String, Object> result) {

        public HandlerResult {
            result = result == null ? Map.of() : Map.copyOf(result);
        }

        public static HandlerResult empty() {
            return new HandlerResult(Map.of());
        }
    }
}
