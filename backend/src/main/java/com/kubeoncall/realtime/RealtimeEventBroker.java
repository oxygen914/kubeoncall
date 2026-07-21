package com.kubeoncall.realtime;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Local replay and subscription boundary.
 *
 * <p>The in-memory implementation is intentionally short-lived. A future durable or distributed
 * implementation can replace this bean without changing the SSE controller.
 */
public interface RealtimeEventBroker {

    PublishResult publish(EventEnvelope event);

    /**
     * Atomically installs a subscriber and replays events after {@code lastEventId}. A blank cursor
     * means "start from now" and does not replay historical events.
     *
     * @throws CursorExpiredException when the cursor is no longer inside the replay window
     */
    Subscription subscribe(String lastEventId, Set<String> topics, Consumer<EventEnvelope> listener);

    int activeSubscriptions();

    record PublishResult(EventEnvelope event, boolean published) {}

    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }

    record CursorGap(
            boolean cursorExpired,
            String requestedEventId,
            String oldestAvailableEventId,
            String latestAvailableEventId) {}

    final class CursorExpiredException extends RuntimeException {

        private final CursorGap gap;

        public CursorExpiredException(CursorGap gap) {
            super("Realtime cursor is outside the replay window: " + gap.requestedEventId());
            this.gap = gap;
        }

        public CursorGap gap() {
            return gap;
        }
    }
}
