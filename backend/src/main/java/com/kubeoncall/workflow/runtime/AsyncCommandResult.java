package com.kubeoncall.workflow.runtime;

import java.util.Map;

/** Idempotent asynchronous command result returned by execution and approval write endpoints. */
public record AsyncCommandResult(Action action, int httpStatus, Map<String, Object> data, String resourcePublicId) {

    public enum Action {
        EXECUTED,
        REPLAY,
        IN_PROGRESS,
        REUSED
    }

    public static AsyncCommandResult executed(Map<String, Object> data, String resourcePublicId) {
        return new AsyncCommandResult(Action.EXECUTED, 202, Map.copyOf(data), resourcePublicId);
    }

    public static AsyncCommandResult replayed(Map<String, Object> data, int httpStatus) {
        return new AsyncCommandResult(Action.REPLAY, httpStatus, Map.copyOf(data), null);
    }

    public static AsyncCommandResult inProgress() {
        return new AsyncCommandResult(Action.IN_PROGRESS, 409, Map.of(), null);
    }

    public static AsyncCommandResult reused() {
        return new AsyncCommandResult(Action.REUSED, 409, Map.of(), null);
    }
}
