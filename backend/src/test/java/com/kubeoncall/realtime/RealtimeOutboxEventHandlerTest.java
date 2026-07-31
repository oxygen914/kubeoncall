package com.kubeoncall.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kubeoncall.audit.outbox.OutboxEvent;

class RealtimeOutboxEventHandlerTest {

    @Test
    void mapsAllInternalAggregateNamesToPluralPublicTopics() {
        assertThat(EventTopic.fromAggregate("alarm", "alarm.updated").wireName())
                .isEqualTo("alarms");
        assertThat(EventTopic.fromAggregate("approval", "approval.updated").wireName())
                .isEqualTo("approvals");
        assertThat(EventTopic.fromAggregate("execution", "execution.updated").wireName())
                .isEqualTo("executions");
        assertThat(EventTopic.fromAggregate("sandbox-run", "sandbox.run.created")
                        .wireName())
                .isEqualTo("sandbox-runs");
        assertThat(EventTopic.fromAggregate("task", "task.updated").wireName()).isEqualTo("tasks");
        assertThat(EventTopic.parse("alarm")).isEmpty();
    }

    @Test
    void mapsOutboxAggregateAndPayloadToPublicEnvelope() throws Exception {
        InMemoryRealtimeEventBroker broker = broker();
        RealtimeEventHub hub = new RealtimeEventHub(broker, emptyTransportProvider());
        RealtimeOutboxEventHandler handler = new RealtimeOutboxEventHandler("approval.decided", objectMapper(), hub);
        ArrayList<EventEnvelope> delivered = new ArrayList<>();
        RealtimeEventBroker.Subscription subscription = broker.subscribe(null, Set.of("approvals"), delivered::add);

        handler.handle(outbox("approval", "apr_1", "approval.decided", "{\"decision\":\"APPROVED\"}"));

        assertThat(delivered).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("oevt_1");
            assertThat(event.topic()).isEqualTo("approvals");
            assertThat(event.type()).isEqualTo("approval.decided");
            assertThat(event.resourceId()).isEqualTo("apr_1");
            assertThat(event.payload().path("decision").asText()).isEqualTo("APPROVED");
            assertThat(event.schemaVersion()).isOne();
        });
        subscription.close();
    }

    @Test
    void redeliveredOutboxEventIsPublishedOnlyOnce() throws Exception {
        InMemoryRealtimeEventBroker broker = broker();
        RealtimeEventHub hub = new RealtimeEventHub(broker, emptyTransportProvider());
        RealtimeOutboxEventHandler handler = new RealtimeOutboxEventHandler("task.created", objectMapper(), hub);
        ArrayList<EventEnvelope> delivered = new ArrayList<>();
        RealtimeEventBroker.Subscription subscription = broker.subscribe(null, Set.of("tasks"), delivered::add);
        OutboxEvent event = outbox("task", "task_1", "task.created", "{}");

        handler.handle(event);
        handler.handle(event);

        assertThat(delivered).hasSize(1);
        subscription.close();
    }

    @Test
    void handlerRejectsWrongEventTypeAndUnsupportedTopic() {
        RealtimeEventHub hub = new RealtimeEventHub(broker(), emptyTransportProvider());
        RealtimeOutboxEventHandler handler = new RealtimeOutboxEventHandler("task.created", objectMapper(), hub);

        assertThatThrownBy(() -> handler.handle(outbox("task", "task_1", "task.finished", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot process");
        RealtimeOutboxEventHandler unsupported = new RealtimeOutboxEventHandler("unknown.changed", objectMapper(), hub);
        assertThatThrownBy(() -> unsupported.handle(outbox("unknown", "unknown_1", "unknown.changed", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported realtime");
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RealtimeClusterTransport> emptyTransportProvider() {
        ObjectProvider<RealtimeClusterTransport> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static InMemoryRealtimeEventBroker broker() {
        return new InMemoryRealtimeEventBroker(
                10, Duration.ofMinutes(10), Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
    }

    private static OutboxEvent outbox(String aggregateType, String resourceId, String eventType, String payload) {
        return new OutboxEvent(
                1L,
                "oevt_1",
                aggregateType,
                resourceId,
                eventType,
                1,
                payload,
                "req_1",
                0,
                10,
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-20T00:00:30Z"));
    }
}
