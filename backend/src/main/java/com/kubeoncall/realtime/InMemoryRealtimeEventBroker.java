package com.kubeoncall.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thread-safe, bounded replay broker for a single application instance.
 *
 * <p>Stable event ids are retained in a slightly larger bounded deduplication window than replay
 * data. This suppresses ordinary outbox redelivery without turning the broker into durable storage.
 */
public final class InMemoryRealtimeEventBroker implements RealtimeEventBroker {

    private final int replayCapacity;
    private final int dedupeCapacity;
    private final Duration retention;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<StoredEvent> replay = new ArrayDeque<>();
    private final Map<String, StoredEvent> replayById = new HashMap<>();
    private final LinkedHashMap<String, EventEnvelope> recentlySeen = new LinkedHashMap<>();
    private final Map<String, LocalSubscription> subscriptions = new HashMap<>();
    private long nextSequence;

    public InMemoryRealtimeEventBroker(int replayCapacity, Duration retention, Clock clock) {
        if (replayCapacity < 1) {
            throw new IllegalArgumentException("replayCapacity must be positive");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.replayCapacity = replayCapacity;
        this.dedupeCapacity = Math.max(replayCapacity, Math.multiplyExact(replayCapacity, 4));
        this.retention = retention;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PublishResult publish(EventEnvelope event) {
        java.util.Objects.requireNonNull(event, "event");
        List<LocalSubscription> listeners;
        lock.lock();
        try {
            pruneExpired(clock.instant());
            EventEnvelope duplicate = recentlySeen.get(event.eventId());
            if (duplicate != null) {
                return new PublishResult(duplicate, false);
            }
            StoredEvent stored = new StoredEvent(++nextSequence, event, clock.instant());
            replay.addLast(stored);
            replayById.put(event.eventId(), stored);
            recentlySeen.put(event.eventId(), event);
            trimToCapacity();
            trimDedupe();
            listeners = subscriptions.values().stream()
                    .filter(subscription -> subscription.accepts(event.topic()))
                    .toList();
        } finally {
            lock.unlock();
        }
        listeners.forEach(listener -> {
            try {
                listener.dispatch(event);
            } catch (RuntimeException listenerFailure) {
                listener.close();
            }
        });
        return new PublishResult(event, true);
    }

    @Override
    public Subscription subscribe(String lastEventId, Set<String> topics, Consumer<EventEnvelope> listener) {
        Set<String> selectedTopics = topics == null ? Set.of() : Set.copyOf(topics);
        if (selectedTopics.isEmpty()) {
            throw new IllegalArgumentException("At least one realtime topic is required");
        }
        java.util.Objects.requireNonNull(listener, "listener");

        String subscriptionId = UUID.randomUUID().toString();
        LocalSubscription subscription = new LocalSubscription(subscriptionId, selectedTopics, listener);
        List<EventEnvelope> pendingReplay;
        lock.lock();
        try {
            pruneExpired(clock.instant());
            pendingReplay = replayAfter(normalize(lastEventId), selectedTopics);
            subscriptions.put(subscriptionId, subscription);
        } finally {
            lock.unlock();
        }
        try {
            subscription.start(pendingReplay);
        } catch (RuntimeException listenerFailure) {
            subscription.close();
            throw listenerFailure;
        }
        return subscription;
    }

    @Override
    public int activeSubscriptions() {
        lock.lock();
        try {
            return subscriptions.size();
        } finally {
            lock.unlock();
        }
    }

    private List<EventEnvelope> replayAfter(String lastEventId, Set<String> topics) {
        if (lastEventId == null) {
            return List.of();
        }
        StoredEvent cursor = replayById.get(lastEventId);
        if (cursor == null) {
            throw new CursorExpiredException(new CursorGap(
                    true,
                    lastEventId,
                    replay.isEmpty() ? null : replay.getFirst().event().eventId(),
                    replay.isEmpty() ? null : replay.getLast().event().eventId()));
        }
        List<EventEnvelope> result = new ArrayList<>();
        for (StoredEvent candidate : replay) {
            if (candidate.sequence() > cursor.sequence()
                    && topics.contains(candidate.event().topic())) {
                result.add(candidate.event());
            }
        }
        return List.copyOf(result);
    }

    private void pruneExpired(Instant now) {
        Instant cutoff = now.minus(retention);
        while (!replay.isEmpty() && replay.getFirst().publishedAt().isBefore(cutoff)) {
            removeOldestReplay();
        }
    }

    private void trimToCapacity() {
        while (replay.size() > replayCapacity) {
            removeOldestReplay();
        }
    }

    private void removeOldestReplay() {
        StoredEvent removed = replay.removeFirst();
        replayById.remove(removed.event().eventId());
    }

    private void trimDedupe() {
        while (recentlySeen.size() > dedupeCapacity) {
            String oldest = recentlySeen.keySet().iterator().next();
            recentlySeen.remove(oldest);
        }
    }

    private void removeSubscription(String id) {
        lock.lock();
        try {
            subscriptions.remove(id);
        } finally {
            lock.unlock();
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record StoredEvent(long sequence, EventEnvelope event, Instant publishedAt) {}

    private final class LocalSubscription implements Subscription {

        private final String id;
        private final Set<String> topics;
        private final Consumer<EventEnvelope> listener;
        private final Deque<EventEnvelope> waiting = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean started;

        private LocalSubscription(String id, Set<String> topics, Consumer<EventEnvelope> listener) {
            this.id = id;
            this.topics = topics;
            this.listener = listener;
        }

        private boolean accepts(String topic) {
            return topics.contains(topic);
        }

        private synchronized void start(List<EventEnvelope> initialReplay) {
            if (closed.get()) {
                return;
            }
            initialReplay.forEach(listener);
            started = true;
            while (!waiting.isEmpty() && !closed.get()) {
                listener.accept(waiting.removeFirst());
            }
        }

        private synchronized void dispatch(EventEnvelope event) {
            if (closed.get()) {
                return;
            }
            if (!started) {
                waiting.addLast(event);
                return;
            }
            listener.accept(event);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (this) {
                    waiting.clear();
                }
                removeSubscription(id);
            }
        }
    }
}
