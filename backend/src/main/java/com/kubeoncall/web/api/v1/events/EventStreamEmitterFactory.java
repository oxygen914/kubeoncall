package com.kubeoncall.web.api.v1.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Creates one long-lived emitter per authenticated stream request. */
@Component
public class EventStreamEmitterFactory {

    private final long timeoutMillis;

    public EventStreamEmitterFactory(
            @Value("${kubeoncall.realtime.connection-timeout-seconds:1800}") long timeoutSeconds) {
        this.timeoutMillis = Math.max(30L, timeoutSeconds) * 1000L;
    }

    public EventStreamEmitter create() {
        return new EventStreamEmitter(timeoutMillis);
    }
}
