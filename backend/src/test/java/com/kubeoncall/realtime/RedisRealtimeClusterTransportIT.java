package com.kubeoncall.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Real Redis WBS-8 proof: a local event from replica A reaches replica B exactly once. */
class RedisRealtimeClusterTransportIT {

    private final List<RedisMessageListenerContainer> listenerContainers = new java.util.ArrayList<>();
    private final List<LettuceConnectionFactory> connectionFactories = new java.util.ArrayList<>();
    private final List<RealtimeEventHub> hubs = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        hubs.forEach(RealtimeEventHub::stop);
        listenerContainers.forEach(this::stopListener);
        connectionFactories.forEach(LettuceConnectionFactory::destroy);
    }

    private void stopListener(RedisMessageListenerContainer container) {
        try {
            container.destroy();
        } catch (Exception ignored) {
            // Test cleanup must not hide a delivery assertion failure.
        }
    }

    @Test
    void publishesFromOneReplicaToAnotherWithoutEchoDuplicate() throws Exception {
        RealtimeEventBroker brokerA = broker();
        RealtimeEventBroker brokerB = broker();
        RealtimeEventHub hubA = hub(brokerA);
        RealtimeEventHub hubB = hub(brokerB);
        hubA.start();
        hubB.start();

        CountDownLatch remoteDelivery = new CountDownLatch(1);
        List<EventEnvelope> locallyDelivered = new CopyOnWriteArrayList<>();
        List<EventEnvelope> remotelyDelivered = new CopyOnWriteArrayList<>();
        try (RealtimeEventBroker.Subscription ignoredA =
                        brokerA.subscribe(null, Set.of(EventTopic.ALARM.wireName()), locallyDelivered::add);
                RealtimeEventBroker.Subscription ignoredB =
                        brokerB.subscribe(null, Set.of(EventTopic.ALARM.wireName()), event -> {
                            remotelyDelivered.add(event);
                            remoteDelivery.countDown();
                        })) {
            EventEnvelope event = new EventEnvelope(
                    "evt_redis_replica_fanout",
                    EventTopic.ALARM.wireName(),
                    "alarm.acknowledged",
                    "alm_redis_replica_fanout",
                    Instant.now(),
                    new ObjectMapper().nullNode(),
                    1);

            assertThat(hubA.publish(event).published()).isTrue();
            assertThat(remoteDelivery.await(5, TimeUnit.SECONDS)).isTrue();
            // Give the echoed Pub/Sub message time to return to A; event-id de-duplication keeps one delivery.
            Thread.sleep(200);
        }

        assertThat(locallyDelivered).extracting(EventEnvelope::eventId).containsExactly("evt_redis_replica_fanout");
        assertThat(remotelyDelivered).extracting(EventEnvelope::eventId).containsExactly("evt_redis_replica_fanout");
    }

    private RealtimeEventHub hub(RealtimeEventBroker broker) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        factory.start();
        connectionFactories.add(factory);
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();
        container.start();
        listenerContainers.add(container);
        RealtimeEventHub hub = new RealtimeEventHub(
                broker,
                provider(new RedisRealtimeClusterTransport(
                        template, new ObjectMapper().findAndRegisterModules(), provider(container))));
        hubs.add(hub);
        return hub;
    }

    private static RealtimeEventBroker broker() {
        return new InMemoryRealtimeEventBroker(32, Duration.ofMinutes(2), Clock.systemUTC());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }
}
