package com.kubeoncall.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Verifies the Redis Pub/Sub cluster transport broadcasts, subscribes and decodes messages. */
class RedisRealtimeClusterTransportTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void broadcastSerializesAndPublishesToChannel() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisRealtimeClusterTransport transport = newTransport(redis, null);
        EventEnvelope event = envelope("evt_1", "alarm.acknowledged");

        transport.broadcast(event);

        verify(redis).convertAndSend(eq("kubeoncall:realtime"), anyString());
    }

    @Test
    void subscribedListenerReceivesDecodedEvent() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        RedisRealtimeClusterTransport transport = newTransport(redis, container);
        List<EventEnvelope> received = new ArrayList<>();
        Consumer<EventEnvelope> listener = received::add;
        transport.subscribe(listener);

        // Simulate another instance publishing: convertAndSend is not used here; instead we drive
        // the listener the transport registered with the container. Capture it.
        org.mockito.ArgumentCaptor<org.springframework.data.redis.connection.MessageListener> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.redis.connection.MessageListener.class);
        verify(container).addMessageListener(captor.capture(), any(Topic.class));
        org.springframework.data.redis.connection.MessageListener registered = captor.getValue();

        EventEnvelope event = envelope("evt_2", "approval.decided");
        byte[] body = objectMapper.writeValueAsBytes(event);
        registered.onMessage(
                new org.springframework.data.redis.connection.DefaultMessage("kubeoncall:realtime".getBytes(), body),
                null);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).eventId()).isEqualTo("evt_2");
    }

    @Test
    void registrationRemovesListener() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        RedisRealtimeClusterTransport transport = newTransport(redis, container);
        List<EventEnvelope> received = new ArrayList<>();
        RealtimeClusterTransport.Registration registration = transport.subscribe(received::add);

        registration.close();

        // After close, a published message should not reach the removed listener.
        org.mockito.ArgumentCaptor<org.springframework.data.redis.connection.MessageListener> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.redis.connection.MessageListener.class);
        verify(container, atLeastOnce()).addMessageListener(captor.capture(), any(Topic.class));
        byte[] body = objectMapper.writeValueAsBytes(envelope("evt_3", "task.created"));
        captor.getValue()
                .onMessage(
                        new org.springframework.data.redis.connection.DefaultMessage(
                                "kubeoncall:realtime".getBytes(), body),
                        null);
        assertThat(received).isEmpty();
    }

    private RedisRealtimeClusterTransport newTransport(
            StringRedisTemplate redis, RedisMessageListenerContainer container) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisMessageListenerContainer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(container);
        return new RedisRealtimeClusterTransport(redis, objectMapper, provider);
    }

    private EventEnvelope envelope(String eventId, String type) {
        return new EventEnvelope(
                eventId,
                "alarms",
                type,
                "alm_1",
                java.time.Instant.parse("2026-07-20T01:00:00Z"),
                objectMapper.nullNode(),
                1);
    }

    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
