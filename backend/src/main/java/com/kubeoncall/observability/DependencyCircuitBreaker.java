package com.kubeoncall.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

/**
 * Small process-local circuit breaker for infrastructure dependencies.
 *
 * <p>It intentionally uses dependency names only, never resource IDs, so its metric cardinality is
 * bounded. A process restart resets its state; cluster-wide failure handling remains an operational
 * concern rather than hidden shared state.
 */
@Component
public class DependencyCircuitBreaker {

    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;
    private final Clock clock;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    @Autowired
    public DependencyCircuitBreaker(KubeOnCallProperties properties, KubeOnCallMetricsService metricsService) {
        this(properties, metricsService, Clock.systemUTC());
    }

    DependencyCircuitBreaker(KubeOnCallProperties properties, KubeOnCallMetricsService metricsService, Clock clock) {
        this.properties = properties;
        this.metricsService = metricsService;
        this.clock = clock;
    }

    public <T> T execute(String dependency, Supplier<T> call) {
        String normalizedDependency = normalizeDependency(dependency);
        if (!properties.getDependencyCircuitBreaker().isEnabled()) {
            return call.get();
        }
        Circuit circuit = circuits.computeIfAbsent(normalizedDependency, ignored -> new Circuit());
        long now = clock.millis();
        if (!circuit.allow(now, resetTimeoutMillis())) {
            metricsService.recordDependencyCircuit(normalizedDependency, "rejected");
            throw new CircuitOpenException(normalizedDependency);
        }
        try {
            T result = call.get();
            if (circuit.recordSuccess()) {
                metricsService.recordDependencyCircuit(normalizedDependency, "closed");
            }
            return result;
        } catch (RuntimeException ex) {
            if (circuit.recordFailure(now, failureThreshold())) {
                metricsService.recordDependencyCircuit(normalizedDependency, "opened");
            }
            throw ex;
        }
    }

    /** Read-only bounded snapshot for the operator Console. No dependency call is made here. */
    public Map<String, CircuitState> snapshot() {
        Map<String, CircuitState> snapshot = new java.util.TreeMap<>();
        circuits.forEach((dependency, circuit) -> snapshot.put(dependency, circuit.snapshot()));
        return Map.copyOf(snapshot);
    }

    private long resetTimeoutMillis() {
        return Duration.ofSeconds(
                        Math.max(1, properties.getDependencyCircuitBreaker().getResetTimeoutSeconds()))
                .toMillis();
    }

    private int failureThreshold() {
        return Math.max(1, properties.getDependencyCircuitBreaker().getFailureThreshold());
    }

    private static String normalizeDependency(String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return "unknown";
        }
        return dependency.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static final class Circuit {

        private int consecutiveFailures;
        private boolean open;
        private boolean halfOpenProbeInFlight;
        private long openedAtMillis;

        synchronized boolean allow(long now, long resetTimeoutMillis) {
            if (!open) {
                return true;
            }
            if (now - openedAtMillis < resetTimeoutMillis || halfOpenProbeInFlight) {
                return false;
            }
            halfOpenProbeInFlight = true;
            return true;
        }

        synchronized boolean recordSuccess() {
            boolean wasOpen = open;
            consecutiveFailures = 0;
            open = false;
            halfOpenProbeInFlight = false;
            return wasOpen;
        }

        synchronized boolean recordFailure(long now, int threshold) {
            halfOpenProbeInFlight = false;
            consecutiveFailures++;
            if (open || consecutiveFailures < threshold) {
                return false;
            }
            open = true;
            openedAtMillis = now;
            return true;
        }

        synchronized CircuitState snapshot() {
            String state = open ? (halfOpenProbeInFlight ? "HALF_OPEN" : "OPEN") : "CLOSED";
            Instant openedAt = openedAtMillis <= 0 ? null : Instant.ofEpochMilli(openedAtMillis);
            return new CircuitState(state, consecutiveFailures, openedAt);
        }
    }

    public record CircuitState(String state, int consecutiveFailures, Instant openedAt) {}

    public static final class CircuitOpenException extends IllegalStateException {

        CircuitOpenException(String dependency) {
            super("Dependency circuit is open: " + dependency);
        }
    }
}
