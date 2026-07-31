package com.kubeoncall.realtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Publishes local events and bridges an optional cluster transport into the local replay broker.
 */
public final class RealtimeEventHub {

    private final RealtimeEventBroker broker;
    private final ObjectProvider<RealtimeClusterTransport> transportProvider;
    private volatile RealtimeClusterTransport.Registration registration;

    public RealtimeEventHub(RealtimeEventBroker broker, ObjectProvider<RealtimeClusterTransport> transportProvider) {
        this.broker = broker;
        this.transportProvider = transportProvider;
    }

    @PostConstruct
    void start() {
        RealtimeClusterTransport transport = transportProvider.getIfAvailable();
        if (transport != null) {
            registration = transport.subscribe(broker::publish);
        }
    }

    public RealtimeEventBroker.PublishResult publish(EventEnvelope event) {
        RealtimeEventBroker.PublishResult result = broker.publish(event);
        RealtimeClusterTransport transport = transportProvider.getIfAvailable();
        if (result.published() && transport != null) {
            transport.broadcast(event);
        }
        return result;
    }

    @PreDestroy
    void stop() {
        RealtimeClusterTransport.Registration current = registration;
        if (current != null) {
            current.close();
        }
    }
}
