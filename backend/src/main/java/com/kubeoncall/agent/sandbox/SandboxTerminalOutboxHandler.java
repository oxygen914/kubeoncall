package com.kubeoncall.agent.sandbox;

import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.outbox.OutboxEvent;
import com.kubeoncall.audit.outbox.OutboxEventHandler;
import com.kubeoncall.realtime.EventEnvelope;
import com.kubeoncall.realtime.EventTopic;
import com.kubeoncall.realtime.RealtimeEventHub;
import com.kubeoncall.task.AsyncTaskRepository;

/** Converts one terminal Sandbox outbox event into a bounded asynchronous recovery task. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SandboxTerminalOutboxHandler implements OutboxEventHandler {

    public static final String EVENT_TYPE = "sandbox.run.terminal";
    public static final String TASK_TYPE = "SANDBOX_WORKFLOW_RECOVERY";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AsyncTaskRepository tasks;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RealtimeEventHub> eventHubProvider;

    public SandboxTerminalOutboxHandler(
            AsyncTaskRepository tasks, ObjectMapper objectMapper, ObjectProvider<RealtimeEventHub> eventHubProvider) {
        this.tasks = tasks;
        this.objectMapper = objectMapper;
        this.eventHubProvider = eventHubProvider;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.payloadJson(), MAP_TYPE);
        String runId = text(payload.get("runId"));
        String executionId = text(payload.get("executionId"));
        if (runId == null || executionId == null) {
            throw new IllegalArgumentException("terminal sandbox event is missing runId or executionId");
        }
        try {
            tasks.create(new AsyncTaskRepository.CreateTask(
                    null,
                    TASK_TYPE,
                    "sandbox-run",
                    runId,
                    "sandbox-workflow-recovery:" + runId,
                    "queued",
                    Map.of("runId", runId, "executionId", executionId, "outboxEventId", event.eventId()),
                    5,
                    Instant.now(),
                    event.requestId(),
                    null));
        } catch (DuplicateKeyException ignored) {
            // The durable unique key (task_type, dedupe_key) makes an Outbox redelivery safe.
        }
        RealtimeEventHub eventHub = eventHubProvider.getIfAvailable();
        if (eventHub != null) {
            eventHub.publish(new EventEnvelope(
                    event.eventId(),
                    EventTopic.SANDBOX.wireName(),
                    event.eventType(),
                    runId,
                    event.createdAt(),
                    objectMapper.valueToTree(payload),
                    event.schemaVersion()));
        }
    }

    private static String text(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isBlank() ? null : text;
    }
}
