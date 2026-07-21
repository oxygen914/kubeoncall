package com.kubeoncall.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kubeoncall.realtime.RealtimeEventBroker.CursorExpiredException;

class InMemoryRealtimeEventBrokerTest {

    @Test
    void replaysOnlyEventsAfterStableCursorAndSelectedTopic() {
        InMemoryRealtimeEventBroker broker = broker(10);
        broker.publish(event("evt_1", "alarms"));
        broker.publish(event("evt_2", "tasks"));
        broker.publish(event("evt_3", "alarms"));

        java.util.ArrayList<EventEnvelope> replayed = new java.util.ArrayList<>();
        RealtimeEventBroker.Subscription subscription = broker.subscribe("evt_1", Set.of("alarms"), replayed::add);

        assertThat(replayed).extracting(EventEnvelope::eventId).containsExactly("evt_3");
        subscription.close();
    }

    @Test
    void duplicatePublishIsIdempotentAndDoesNotRedeliver() {
        InMemoryRealtimeEventBroker broker = broker(10);
        java.util.ArrayList<EventEnvelope> delivered = new java.util.ArrayList<>();
        RealtimeEventBroker.Subscription subscription = broker.subscribe(null, Set.of("alarms"), delivered::add);

        RealtimeEventBroker.PublishResult first = broker.publish(event("evt_1", "alarms"));
        RealtimeEventBroker.PublishResult duplicate = broker.publish(event("evt_1", "alarms"));

        assertThat(first.published()).isTrue();
        assertThat(duplicate.published()).isFalse();
        assertThat(delivered).hasSize(1);
        subscription.close();
    }

    @Test
    void evictedCursorProducesExplicitGapMetadata() {
        InMemoryRealtimeEventBroker broker = broker(2);
        broker.publish(event("evt_1", "alarms"));
        broker.publish(event("evt_2", "alarms"));
        broker.publish(event("evt_3", "alarms"));

        assertThatThrownBy(() -> broker.subscribe("evt_1", Set.of("alarms"), ignored -> {}))
                .isInstanceOfSatisfying(CursorExpiredException.class, ex -> {
                    assertThat(ex.gap().cursorExpired()).isTrue();
                    assertThat(ex.gap().requestedEventId()).isEqualTo("evt_1");
                    assertThat(ex.gap().oldestAvailableEventId()).isEqualTo("evt_2");
                    assertThat(ex.gap().latestAvailableEventId()).isEqualTo("evt_3");
                });
    }

    @Test
    void expirationProducesGapEvenWhenCapacityHasRoom() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        InMemoryRealtimeEventBroker broker = new InMemoryRealtimeEventBroker(10, Duration.ofSeconds(5), clock);
        broker.publish(event("evt_1", "alarms"));
        clock.advance(Duration.ofSeconds(6));

        assertThatThrownBy(() -> broker.subscribe("evt_1", Set.of("alarms"), ignored -> {}))
                .isInstanceOfSatisfying(CursorExpiredException.class, ex -> {
                    assertThat(ex.gap().oldestAvailableEventId()).isNull();
                    assertThat(ex.gap().latestAvailableEventId()).isNull();
                });
    }

    @Test
    void concurrentPublishKeepsEveryDistinctEventAndSuppressesDuplicates() throws Exception {
        int eventCount = 300;
        InMemoryRealtimeEventBroker broker = broker(eventCount + 10);
        Set<String> delivered = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(eventCount);
        RealtimeEventBroker.Subscription subscription = broker.subscribe(null, Set.of("alarms"), event -> {
            if (delivered.add(event.eventId())) {
                latch.countDown();
            }
        });
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Runnable> publishes = IntStream.range(0, eventCount)
                    .mapToObj(index -> (Runnable) () -> {
                        EventEnvelope event = event("evt_" + index, "alarms");
                        broker.publish(event);
                        broker.publish(event);
                    })
                    .toList();
            for (Runnable publish : publishes) {
                executor.submit(publish);
            }
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(delivered).hasSize(eventCount);
        } finally {
            executor.shutdownNow();
            subscription.close();
        }
    }

    @Test
    void closingSubscriptionRemovesItAndStopsDelivery() {
        InMemoryRealtimeEventBroker broker = broker(10);
        java.util.ArrayList<EventEnvelope> delivered = new java.util.ArrayList<>();
        RealtimeEventBroker.Subscription subscription = broker.subscribe(null, Set.of("alarms"), delivered::add);
        assertThat(broker.activeSubscriptions()).isOne();

        subscription.close();
        subscription.close();
        broker.publish(event("evt_1", "alarms"));

        assertThat(broker.activeSubscriptions()).isZero();
        assertThat(delivered).isEmpty();
    }

    private static InMemoryRealtimeEventBroker broker(int capacity) {
        return new InMemoryRealtimeEventBroker(
                capacity, Duration.ofMinutes(10), Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
    }

    private static EventEnvelope event(String eventId, String topic) {
        return new EventEnvelope(
                eventId,
                topic,
                topic + ".updated",
                topic + "_1",
                Instant.parse("2026-07-20T00:00:00Z"),
                JsonNodeFactory.instance.objectNode().put("id", topic + "_1"),
                1);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
