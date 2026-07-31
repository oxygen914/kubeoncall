package com.kubeoncall.web.api.v1.events;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kubeoncall.realtime.EventEnvelope;
import com.kubeoncall.realtime.EventTopic;
import com.kubeoncall.realtime.HeartbeatScheduler;
import com.kubeoncall.realtime.RealtimeEventBroker;
import com.kubeoncall.realtime.RealtimeEventBroker.CursorExpiredException;
import com.kubeoncall.task.TaskPermissionPolicy;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/** Authenticated, permission-filtered SSE stream backed by the short replay broker. */
@RestController
@RequestMapping("/api/v1/events")
public class EventStreamController {

    private final RealtimeEventBroker broker;
    private final HeartbeatScheduler heartbeatScheduler;
    private final EventStreamEmitterFactory emitterFactory;
    private final V1Security security;

    public EventStreamController(
            RealtimeEventBroker broker,
            HeartbeatScheduler heartbeatScheduler,
            EventStreamEmitterFactory emitterFactory,
            V1Security security) {
        this.broker = broker;
        this.heartbeatScheduler = heartbeatScheduler;
        this.emitterFactory = emitterFactory;
        this.security = security;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestParam(name = "lastEventId", required = false) String lastEventIdQuery,
            @RequestParam(name = "topics", required = false) List<String> requestedTopics) {
        V1Principal principal = security.requireAuthenticated();
        Set<String> topics = authorizeTopics(requestedTopics, principal);
        String cursor = validateCursor(firstNonBlank(lastEventIdHeader, lastEventIdQuery));
        EventStreamEmitter stream = emitterFactory.create();
        Connection connection = new Connection(stream, principal.permissions());
        stream.onClose(connection::closedByEmitter);

        try {
            connection.attach(broker.subscribe(cursor, topics, connection::send));
            connection.heartbeat(heartbeatScheduler.schedule(connection::heartbeat));
        } catch (CursorExpiredException ex) {
            connection.cursorExpired(ex);
            return stream.emitter();
        } catch (RuntimeException ex) {
            connection.abort();
            throw ex;
        }
        return stream.emitter();
    }

    private Set<String> authorizeTopics(List<String> requestedTopics, V1Principal principal) {
        Set<EventTopic> parsed = parseTopics(requestedTopics);
        if (parsed.isEmpty()) {
            Arrays.stream(EventTopic.values())
                    .filter(topic -> topic.isAuthorized(principal.permissions()))
                    .forEach(parsed::add);
            if (parsed.isEmpty()) {
                throw V1ApiException.forbidden("No realtime topic is available to this principal");
            }
        } else {
            parsed.stream()
                    .filter(topic -> !topic.isAuthorized(principal.permissions()))
                    .findFirst()
                    .ifPresent(topic -> {
                        throw V1ApiException.forbidden(
                                "Insufficient permissions for realtime topic: " + topic.wireName());
                    });
        }
        return parsed.stream().map(EventTopic::wireName).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<EventTopic> parseTopics(List<String> requestedTopics) {
        Set<EventTopic> result = EnumSet.noneOf(EventTopic.class);
        if (requestedTopics == null) {
            return result;
        }
        for (String raw : requestedTopics) {
            if (raw == null) {
                continue;
            }
            for (String value : raw.split(",")) {
                if (value.isBlank()) {
                    continue;
                }
                EventTopic topic = EventTopic.parse(value)
                        .orElseThrow(() -> new V1ApiException(
                                400, V1ApiErrorCode.INVALID_REQUEST, "Unsupported realtime topic: " + value.trim()));
                result.add(topic);
            }
        }
        return result;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    private static String validateCursor(String cursor) {
        if (cursor != null && (cursor.length() > 256 || cursor.indexOf('\r') >= 0 || cursor.indexOf('\n') >= 0)) {
            throw new V1ApiException(400, V1ApiErrorCode.INVALID_REQUEST, "Invalid Last-Event-ID");
        }
        return cursor;
    }

    private static final class Connection {

        private final EventStreamEmitter stream;
        private final Set<String> permissions;
        private final AtomicBoolean closed = new AtomicBoolean();
        private RealtimeEventBroker.Subscription subscription;
        private HeartbeatScheduler.Registration heartbeat;

        private Connection(EventStreamEmitter stream, Set<String> permissions) {
            this.stream = stream;
            this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        private synchronized void attach(RealtimeEventBroker.Subscription value) {
            if (closed.get()) {
                value.close();
            } else {
                subscription = value;
            }
        }

        private synchronized void heartbeat(HeartbeatScheduler.Registration value) {
            if (closed.get()) {
                value.close();
            } else {
                heartbeat = value;
            }
        }

        private void send(EventEnvelope event) {
            if (closed.get() || !canRead(event)) {
                return;
            }
            try {
                stream.send(event);
            } catch (IOException | IllegalStateException ex) {
                fail(ex);
            }
        }

        private boolean canRead(EventEnvelope event) {
            if (!EventTopic.TASK.wireName().equals(event.topic())) {
                return true;
            }
            String taskType = event.payload() == null
                    ? null
                    : event.payload().path("taskType").asText(null);
            String resourceType = event.payload() == null ? null : firstText(event, "resourceType", "aggregateType");
            return permissions.contains(TaskPermissionPolicy.requiredPermission(taskType, resourceType));
        }

        private static String firstText(EventEnvelope event, String primary, String fallback) {
            String value = event.payload().path(primary).asText(null);
            return value == null || value.isBlank()
                    ? event.payload().path(fallback).asText(null)
                    : value;
        }

        private void heartbeat() {
            if (closed.get()) {
                return;
            }
            try {
                stream.heartbeat();
            } catch (IOException | IllegalStateException ex) {
                fail(ex);
            }
        }

        private void cursorExpired(CursorExpiredException ex) {
            try {
                stream.cursorGap(ex.gap());
                stream.complete();
            } catch (IOException | IllegalStateException sendError) {
                stream.completeWithError(sendError);
            } finally {
                closeResources();
            }
        }

        private void fail(Throwable error) {
            closeResources();
            stream.completeWithError(error);
        }

        private void closedByEmitter() {
            closeResources();
        }

        private void abort() {
            closeResources();
        }

        private synchronized void closeResources() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (subscription != null) {
                subscription.close();
                subscription = null;
            }
            if (heartbeat != null) {
                heartbeat.close();
                heartbeat = null;
            }
        }
    }
}
