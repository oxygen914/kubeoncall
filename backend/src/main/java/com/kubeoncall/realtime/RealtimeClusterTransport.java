package com.kubeoncall.realtime;

import java.util.function.Consumer;

/**
 * Optional multi-instance fanout SPI.
 *
 * <p>A Redis Pub/Sub or Streams adapter may implement this interface. No distributed transport is
 * required for the single-instance baseline; event-id deduplication makes echoed messages safe.
 */
public interface RealtimeClusterTransport {

    void broadcast(EventEnvelope event);

    Registration subscribe(Consumer<EventEnvelope> listener);

    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
