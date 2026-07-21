package com.kubeoncall.web.api.v1.clientevents;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Small in-process guard against a broken browser flooding structured logs.
 *
 * <p>The limiter is deliberately best-effort and instance-local: client telemetry is diagnostic,
 * never a correctness signal. The bounded map prevents attacker-controlled fingerprints from
 * becoming an unbounded allocation.
 */
@Component
public class ClientEventRateLimiter {

    static final int DEFAULT_MAX_ENTRIES = 10_000;
    static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final int maxEntries;
    private final long windowMillis;
    private final Map<String, Long> acceptedAt = new LinkedHashMap<>();

    public ClientEventRateLimiter() {
        this(Clock.systemUTC(), DEFAULT_MAX_ENTRIES, DEFAULT_WINDOW);
    }

    ClientEventRateLimiter(Clock clock, int maxEntries, Duration window) {
        if (clock == null || maxEntries < 1 || window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Client event limiter requires a clock, positive capacity and window");
        }
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.windowMillis = window.toMillis();
    }

    public synchronized boolean tryAcquire(String principalId, String errorCode, String route) {
        String fingerprint = principalId + '\u0000' + errorCode + '\u0000' + route;
        long now = clock.millis();
        Long previous = acceptedAt.get(fingerprint);
        if (previous != null && now - previous < windowMillis) {
            return false;
        }
        acceptedAt.remove(fingerprint);
        while (acceptedAt.size() >= maxEntries) {
            String oldest = acceptedAt.keySet().iterator().next();
            acceptedAt.remove(oldest);
        }
        acceptedAt.put(fingerprint, now);
        return true;
    }

    synchronized int trackedFingerprintCount() {
        return acceptedAt.size();
    }
}
