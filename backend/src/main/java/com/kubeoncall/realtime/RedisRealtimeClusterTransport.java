package com.kubeoncall.realtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis Pub/Sub implementation of {@link RealtimeClusterTransport} (WBS-8 multi-instance fanout).
 * Each instance publishes every locally-emitted event to {@code kubeoncall:realtime} and subscribes
 * to the same channel; messages received from other instances are handed to the local broker, which
 * deduplicates by {@code eventId} so an event the instance itself emitted is not replayed to its own
 * connections. This lets an event emitted on instance A reach a browser connected to instance B.
 *
 * <p>Activated only when {@code kubeoncall.realtime.cluster-transport=redis}; absent that flag the
 * single-instance in-memory path is used and no Redis listener container is wired.
 */
@Component
@ConditionalOnProperty(prefix = "kubeoncall.realtime", name = "cluster-transport", havingValue = "redis")
public class RedisRealtimeClusterTransport implements RealtimeClusterTransport {

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeClusterTransport.class);
    static final String CHANNEL = "kubeoncall:realtime";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RedisMessageListenerContainer> containerProvider;
    private final CopyOnWriteArrayList<Consumer<EventEnvelope>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<RedisMessageListenerContainer, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    public RedisRealtimeClusterTransport(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.containerProvider = containerProvider;
    }

    @Override
    public void broadcast(EventEnvelope event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
        } catch (Exception ex) {
            log.warn("Failed to broadcast realtime event over Redis: {}", ex.getMessage());
        }
    }

    @Override
    public Registration subscribe(Consumer<EventEnvelope> listener) {
        listeners.add(listener);
        RedisMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container == null) {
            log.warn("RedisMessageListenerContainer unavailable; cluster transport receive is disabled");
            return () -> listeners.remove(listener);
        }
        // Subscribe once per container; the listener fans out to all local subscribers.
        Subscription existing = subscriptions.get(container);
        if (existing == null) {
            RedisListener redisListener = new RedisListener();
            container.addMessageListener(redisListener, new ChannelTopic(CHANNEL));
            subscriptions.put(container, new Subscription(redisListener));
        }
        return () -> listeners.remove(listener);
    }

    private void dispatch(EventEnvelope event) {
        for (Consumer<EventEnvelope> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception ex) {
                log.warn("Realtime cluster listener threw: {}", ex.getMessage());
            }
        }
    }

    private final class RedisListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            try {
                EventEnvelope event = objectMapper.readValue(message.getBody(), EventEnvelope.class);
                dispatch(event);
            } catch (Exception ex) {
                log.warn("Failed to decode realtime cluster message: {}", ex.getMessage());
            }
        }
    }

    private record Subscription(RedisListener listener) {}

    @FunctionalInterface
    private interface Registration extends RealtimeClusterTransport.Registration {}
}
