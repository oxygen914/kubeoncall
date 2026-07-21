package com.kubeoncall.audit.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import com.kubeoncall.audit.outbox.OutboxRepository.FailureDisposition;
import com.kubeoncall.common.concurrent.LeaseHeartbeat;
import com.kubeoncall.observability.CorrelationContext;
import com.kubeoncall.observability.SensitiveDataRedactor;

/**
 * Synchronous outbox drain primitive. A scheduler may invoke {@link #runOnce()}, but this class
 * deliberately owns no scheduling policy.
 */
public final class OutboxWorker {

    private static final int MAX_ERROR_SUMMARY_LENGTH = 1000;
    private static final SensitiveDataRedactor REDACTOR = SensitiveDataRedactor.STANDARD;

    private final OutboxRepository repository;
    private final OutboxEventHandlerRegistry registry;
    private final Clock clock;
    private final String ownerToken;
    private final Duration leaseDuration;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int batchSize;
    private final HeartbeatStarter heartbeatStarter;

    public OutboxWorker(
            OutboxRepository repository,
            OutboxEventHandlerRegistry registry,
            Clock clock,
            String ownerToken,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maxBackoff,
            int batchSize) {
        this(
                repository,
                registry,
                clock,
                ownerToken,
                leaseDuration,
                initialBackoff,
                maxBackoff,
                batchSize,
                OutboxWorker::startLeaseHeartbeat);
    }

    OutboxWorker(
            OutboxRepository repository,
            OutboxEventHandlerRegistry registry,
            Clock clock,
            String ownerToken,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maxBackoff,
            int batchSize,
            HeartbeatStarter heartbeatStarter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerToken = requireText(ownerToken, "ownerToken");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.initialBackoff = requirePositive(initialBackoff, "initialBackoff");
        this.maxBackoff = requirePositive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.heartbeatStarter = Objects.requireNonNull(heartbeatStarter, "heartbeatStarter");
    }

    /**
     * Claims a batch in the repository's short transaction, then invokes each external handler
     * outside that transaction.
     */
    public RunResult runOnce() {
        Instant claimTime = clock.instant();
        List<OutboxEvent> events = repository.claimBatch(ownerToken, claimTime, leaseDuration, batchSize);
        MutableResult result = new MutableResult(events.size());
        for (OutboxEvent event : events) {
            process(event, result);
        }
        return result.freeze();
    }

    private void process(OutboxEvent event, MutableResult result) {
        try (CorrelationContext.Scope ignored = CorrelationContext.open(correlation(event))) {
            processScoped(event, result);
        }
    }

    private void processScoped(OutboxEvent event, MutableResult result) {
        Instant renewalTime = clock.instant();
        if (!repository.renewLease(event.id(), ownerToken, renewalTime, leaseDuration)) {
            result.leaseLost++;
            return;
        }

        BooleanSupplier renewLease =
                () -> repository.renewLease(event.id(), ownerToken, clock.instant(), leaseDuration);
        try (Heartbeat heartbeat =
                heartbeatStarter.start(leaseDuration, renewLease, "outbox-heartbeat-" + event.eventId())) {
            OutboxEventHandler handler = registry.find(event.eventType()).orElse(null);
            if (handler == null) {
                recordFailure(
                        event,
                        heartbeat,
                        "UNKNOWN_EVENT_TYPE",
                        "No outbox handler is registered for event type " + event.eventType(),
                        result);
                return;
            }

            try {
                handler.handle(event);
            } catch (Exception exception) {
                recordFailure(
                        event,
                        heartbeat,
                        "HANDLER_FAILURE",
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception),
                        result);
                return;
            }

            if (!requireValid(heartbeat, "Outbox lease was lost before publishing", result)) {
                return;
            }
            if (repository.markPublished(event.id(), ownerToken, clock.instant())) {
                result.published++;
            } else {
                result.leaseLost++;
            }
        }
    }

    private void recordFailure(
            OutboxEvent event, Heartbeat heartbeat, String code, String summary, MutableResult result) {
        if (!requireValid(heartbeat, "Outbox lease was lost before failure recording", result)) {
            return;
        }
        FailureDisposition disposition = repository.markFailure(
                event.id(), ownerToken, clock.instant(), code, safeSummary(summary), initialBackoff, maxBackoff);
        switch (disposition) {
            case RETRY_SCHEDULED -> result.retryScheduled++;
            case DEAD_LETTERED -> result.deadLettered++;
            case LEASE_LOST -> result.leaseLost++;
        }
    }

    private static boolean requireValid(Heartbeat heartbeat, String message, MutableResult result) {
        try {
            heartbeat.requireValid(message);
            return true;
        } catch (IllegalStateException leaseLost) {
            result.leaseLost++;
            return false;
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        String safe = message == null || message.isBlank() ? "handler raised an exception" : message.trim();
        return REDACTOR.redactText(safe);
    }

    private static String safeSummary(String summary) {
        String safe =
                summary == null || summary.isBlank() ? "Outbox event failed" : REDACTOR.redactText(summary.trim());
        return safe.length() <= MAX_ERROR_SUMMARY_LENGTH ? safe : safe.substring(0, MAX_ERROR_SUMMARY_LENGTH);
    }

    private static Map<String, Object> correlation(OutboxEvent event) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(CorrelationContext.REQUEST_ID, event.requestId());
        values.put(CorrelationContext.TRACE_ID, event.requestId());
        values.put(CorrelationContext.ROUTE, "worker:outbox");
        values.put(CorrelationContext.EVENT_ID, event.eventId());
        values.put(CorrelationContext.EVENT_TYPE, event.eventType());
        values.put(CorrelationContext.AUTH_METHOD, "OUTBOX");
        return values;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Duration requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }

    String ownerToken() {
        return ownerToken;
    }

    private static Heartbeat startLeaseHeartbeat(Duration leaseTtl, BooleanSupplier renewLease, String threadName) {
        LeaseHeartbeat delegate = LeaseHeartbeat.start(leaseTtl, renewLease, threadName);
        return new Heartbeat() {
            @Override
            public void requireValid(String message) {
                delegate.requireValid(message);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    interface Heartbeat extends AutoCloseable {

        void requireValid(String message);

        @Override
        void close();
    }

    @FunctionalInterface
    interface HeartbeatStarter {

        Heartbeat start(Duration leaseTtl, BooleanSupplier renewLease, String threadName);
    }

    public record RunResult(int claimed, int published, int retryScheduled, int deadLettered, int leaseLost) {}

    private static final class MutableResult {

        private final int claimed;
        private int published;
        private int retryScheduled;
        private int deadLettered;
        private int leaseLost;

        private MutableResult(int claimed) {
            this.claimed = claimed;
        }

        private RunResult freeze() {
            return new RunResult(claimed, published, retryScheduled, deadLettered, leaseLost);
        }
    }
}
