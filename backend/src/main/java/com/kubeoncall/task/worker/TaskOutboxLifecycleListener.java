package com.kubeoncall.task.worker;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.audit.OutboxWriter.OutboxEvent;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.worker.AsyncTaskWorker.RunResult;

/** Publishes task transitions to the durable outbox consumed by the realtime SSE projection. */
@Component
public final class TaskOutboxLifecycleListener implements AsyncTaskLifecycleListener {

    private final OutboxWriter outboxWriter;

    public TaskOutboxLifecycleListener(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void onTransition(AsyncTaskRecord claimedTask, RunResult result) {
        if (!outboxWriter.isAvailable()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", result.taskPublicId());
        payload.put("taskType", result.taskType());
        payload.put("status", publicStatus(result));
        payload.put("attempt", result.attempt());
        putIfPresent(payload, "errorCode", result.errorCode());
        putIfPresent(payload, "errorSummary", result.errorSummary());
        putIfPresent(payload, "nextAttemptAt", result.nextAttemptAt());
        putIfPresent(payload, "resourceType", claimedTask.resourceType());
        putIfPresent(payload, "resourceId", claimedTask.resourcePublicId());

        outboxWriter.enqueue(
                OutboxEvent.of("task", result.taskPublicId(), "task.updated", payload, claimedTask.requestId()));
    }

    private static String publicStatus(RunResult result) {
        return switch (result.outcome()) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case RETRY_SCHEDULED -> "RETRY";
            case DEAD_LETTERED -> "DEAD_LETTER";
            case IDLE, LEASE_LOST ->
                throw new IllegalArgumentException("Only accepted task transitions can be published");
        };
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
