package com.kubeoncall.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

class RealtimeEventHubTest {

    @Test
    void optionalClusterTransportFansOutLocalEventsAndAcceptsRemoteEvents() {
        InMemoryRealtimeEventBroker broker = new InMemoryRealtimeEventBroker(
                10, Duration.ofMinutes(10), Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
        RealtimeClusterTransport transport = mock(RealtimeClusterTransport.class);
        RealtimeClusterTransport.Registration transportRegistration = mock(RealtimeClusterTransport.Registration.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RealtimeClusterTransport> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(transport);
        ArgumentCaptor<Consumer<EventEnvelope>> remoteListener = consumerCaptor();
        when(transport.subscribe(remoteListener.capture())).thenReturn(transportRegistration);
        RealtimeEventHub hub = new RealtimeEventHub(broker, provider);
        ArrayList<EventEnvelope> received = new ArrayList<>();
        RealtimeEventBroker.Subscription subscription = broker.subscribe(null, Set.of("alarms"), received::add);

        hub.start();
        EventEnvelope local = event("evt_local");
        hub.publish(local);
        EventEnvelope remote = event("evt_remote");
        remoteListener.getValue().accept(remote);
        hub.stop();

        verify(transport).broadcast(local);
        verify(transportRegistration).close();
        assertThat(received).extracting(EventEnvelope::eventId).containsExactly("evt_local", "evt_remote");
        subscription.close();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Consumer<EventEnvelope>> consumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Consumer.class);
    }

    private static EventEnvelope event(String id) {
        return new EventEnvelope(
                id,
                "alarms",
                "alarm.updated",
                "alarm_1",
                Instant.parse("2026-07-20T00:00:00Z"),
                JsonNodeFactory.instance.objectNode(),
                1);
    }
}
