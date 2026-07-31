package com.kubeoncall.web.api.v1.events;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.realtime.EventEnvelope;
import com.kubeoncall.realtime.HeartbeatScheduler;
import com.kubeoncall.realtime.RealtimeEventBroker;
import com.kubeoncall.realtime.RealtimeEventBroker.CursorExpiredException;
import com.kubeoncall.realtime.RealtimeEventBroker.CursorGap;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

class EventStreamControllerContractTest {

    private RealtimeEventBroker broker;
    private HeartbeatScheduler scheduler;
    private EventStreamEmitterFactory factory;
    private EventStreamEmitter stream;
    private V1Security security;
    private EventStreamController controller;

    @BeforeEach
    void setUp() {
        broker = mock(RealtimeEventBroker.class);
        scheduler = mock(HeartbeatScheduler.class);
        factory = mock(EventStreamEmitterFactory.class);
        stream = mock(EventStreamEmitter.class);
        security = mock(V1Security.class);
        when(factory.create()).thenReturn(stream);
        when(stream.emitter()).thenReturn(new SseEmitter());
        when(scheduler.schedule(any())).thenReturn(mock(HeartbeatScheduler.Registration.class));
        when(broker.subscribe(any(), any(), any())).thenReturn(mock(RealtimeEventBroker.Subscription.class));
        when(security.requireAuthenticated())
                .thenReturn(principal(Set.of(
                        PermissionCode.ALARM_READ,
                        PermissionCode.APPROVAL_READ,
                        PermissionCode.EXECUTION_READ,
                        PermissionCode.KNOWLEDGE_READ,
                        PermissionCode.MEMORY_READ,
                        PermissionCode.SKILL_READ)));
        controller = new EventStreamController(broker, scheduler, factory, security);
    }

    @Test
    void explicitTopicsRequireAnAuthorizedPrincipalAndHeaderCursorWins() {
        controller.stream("evt_header", "evt_query", List.of("alarms,tasks", "approvals"));

        verify(security).requireAuthenticated();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> topics = ArgumentCaptor.forClass(Set.class);
        verify(broker).subscribe(eq("evt_header"), topics.capture(), any());
        org.assertj.core.api.Assertions.assertThat(topics.getValue())
                .containsExactlyInAnyOrder("alarms", "approvals", "tasks");
    }

    @Test
    void omittedTopicsSelectOnlyTopicsGrantedToAuthenticatedPrincipal() {
        V1Principal principal = principal(Set.of(PermissionCode.ALARM_READ, PermissionCode.EXECUTION_READ));
        when(security.requireAuthenticated()).thenReturn(principal);

        controller.stream(null, "evt_query", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> topics = ArgumentCaptor.forClass(Set.class);
        verify(broker).subscribe(eq("evt_query"), topics.capture(), any());
        org.assertj.core.api.Assertions.assertThat(topics.getValue())
                .containsExactlyInAnyOrder("alarms", "executions", "tasks");
        verify(security, never()).requirePermission(any());
    }

    @Test
    void noAuthorizedDefaultTopicIsRejectedBeforeOpeningEmitter() {
        V1Principal principal = principal(Set.of());
        when(security.requireAuthenticated()).thenReturn(principal);

        assertThatThrownBy(() -> controller.stream(null, null, null))
                .isInstanceOf(V1ApiException.class)
                .extracting(error -> ((V1ApiException) error).status())
                .isEqualTo(403);
        verify(factory, never()).create();
    }

    @Test
    void invalidTopicIsRejectedBeforeOpeningEmitter() {
        assertThatThrownBy(() -> controller.stream(null, null, List.of("alarms,secrets")))
                .isInstanceOf(V1ApiException.class)
                .hasMessageContaining("Unsupported realtime topic");
        verify(factory, never()).create();
    }

    @Test
    void brokerEventsAndScheduledHeartbeatReachEmitter() throws Exception {
        AtomicReference<Consumer<EventEnvelope>> listener = new AtomicReference<>();
        when(broker.subscribe(eq(null), eq(Set.of("alarms")), any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(2));
            return mock(RealtimeEventBroker.Subscription.class);
        });
        AtomicReference<Runnable> heartbeat = new AtomicReference<>();
        when(scheduler.schedule(any())).thenAnswer(invocation -> {
            heartbeat.set(invocation.getArgument(0));
            return mock(HeartbeatScheduler.Registration.class);
        });

        controller.stream(null, null, List.of("alarms"));
        EventEnvelope event = event();
        listener.get().accept(event);
        heartbeat.get().run();

        verify(stream).send(event);
        verify(stream).heartbeat();
    }

    @Test
    void taskEventsAreFilteredByTheirOwningDomainPermission() throws Exception {
        V1Principal principal = principal(Set.of(PermissionCode.KNOWLEDGE_READ));
        when(security.requireAuthenticated()).thenReturn(principal);
        AtomicReference<Consumer<EventEnvelope>> listener = new AtomicReference<>();
        when(broker.subscribe(eq(null), eq(Set.of("tasks")), any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(2));
            return mock(RealtimeEventBroker.Subscription.class);
        });

        controller.stream(null, null, List.of("tasks"));
        EventEnvelope memoryTask = taskEvent("evt_memory", "MEMORY_EXTRACTION", "memory_extraction");
        EventEnvelope knowledgeTask = taskEvent("evt_knowledge", "KNOWLEDGE_IMPORT", "knowledge_import");
        listener.get().accept(memoryTask);
        listener.get().accept(knowledgeTask);

        verify(stream, never()).send(memoryTask);
        verify(stream).send(knowledgeTask);
    }

    @Test
    void emitterDisconnectClosesBrokerSubscriptionAndHeartbeat() {
        RealtimeEventBroker.Subscription subscription = mock(RealtimeEventBroker.Subscription.class);
        HeartbeatScheduler.Registration heartbeat = mock(HeartbeatScheduler.Registration.class);
        when(broker.subscribe(any(), any(), any())).thenReturn(subscription);
        when(scheduler.schedule(any())).thenReturn(heartbeat);
        ArgumentCaptor<Runnable> cleanup = ArgumentCaptor.forClass(Runnable.class);

        controller.stream(null, null, List.of("alarms"));
        verify(stream).onClose(cleanup.capture());
        cleanup.getValue().run();
        cleanup.getValue().run();

        verify(subscription).close();
        verify(heartbeat).close();
    }

    @Test
    void expiredCursorEmitsGapAndDoesNotScheduleHeartbeat() throws IOException {
        CursorGap gap = new CursorGap(true, "evt_old", "evt_2", "evt_3");
        when(broker.subscribe(eq("evt_old"), any(), any())).thenThrow(new CursorExpiredException(gap));

        controller.stream("evt_old", null, List.of("alarms"));

        verify(stream).cursorGap(gap);
        verify(stream).complete();
        verify(scheduler, never()).schedule(any());
    }

    @Test
    void sendFailureClosesResourcesAndCompletesWithError() throws Exception {
        RealtimeEventBroker.Subscription subscription = mock(RealtimeEventBroker.Subscription.class);
        AtomicReference<Consumer<EventEnvelope>> listener = new AtomicReference<>();
        when(broker.subscribe(any(), any(), any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(2));
            return subscription;
        });
        IOException failure = new IOException("client disconnected");
        org.mockito.Mockito.doThrow(failure).when(stream).send(any());

        controller.stream(null, null, List.of("alarms"));
        listener.get().accept(event());

        verify(subscription).close();
        verify(stream).completeWithError(failure);
    }

    private static EventEnvelope event() {
        return new EventEnvelope(
                "evt_1",
                "alarms",
                "alarm.updated",
                "alarm_1",
                Instant.parse("2026-07-20T00:00:00Z"),
                JsonNodeFactory.instance.objectNode().put("status", "FIRING"),
                1);
    }

    private static EventEnvelope taskEvent(String eventId, String taskType, String resourceType) {
        return new EventEnvelope(
                eventId,
                "tasks",
                "task.updated",
                "task_1",
                Instant.parse("2026-07-20T00:00:00Z"),
                JsonNodeFactory.instance.objectNode().put("taskType", taskType).put("resourceType", resourceType),
                1);
    }

    private static V1Principal principal(Set<String> permissions) {
        UserAccount user = new UserAccount(
                1L,
                "usr_1",
                "operator",
                "Operator",
                null,
                null,
                null,
                1L,
                "ACTIVE",
                1L,
                null,
                null,
                null,
                0,
                Set.of("OPERATOR"),
                permissions);
        return new V1Principal(user, permissions, V1Principal.AuthMethod.SESSION);
    }
}
