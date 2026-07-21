package com.kubeoncall.audit.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import com.kubeoncall.audit.outbox.OutboxRepository.FailureDisposition;
import com.kubeoncall.audit.outbox.OutboxWorker.Heartbeat;
import com.kubeoncall.audit.outbox.OutboxWorker.RunResult;
import com.kubeoncall.observability.CorrelationContext;

class OutboxWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OWNER = "worker-a";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 20;

    @Test
    void publishesHandledEventUsingStableEventId() throws Exception {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        CapturingHandler handler = new CapturingHandler(event.eventType());
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true);
        when(repository.markPublished(event.id(), OWNER, NOW)).thenReturn(true);

        RunResult result = worker(repository, List.of(handler)).runOnce();

        assertEquals(new RunResult(1, 1, 0, 0, 0), result);
        assertSame(event, handler.handled);
        assertEquals("oevt_123", handler.handled.idempotencyKey());
        verify(repository, never()).markFailure(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void schedulesRetryWhenHandlerFails() throws Exception {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        when(handler.eventType()).thenReturn(event.eventType());
        doThrow(new IllegalStateException("downstream unavailable"))
                .when(handler)
                .handle(event);
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true);
        when(repository.markFailure(
                        eq(event.id()),
                        eq(OWNER),
                        eq(NOW),
                        eq("HANDLER_FAILURE"),
                        eq("IllegalStateException: downstream unavailable"),
                        eq(INITIAL_BACKOFF),
                        eq(MAX_BACKOFF)))
                .thenReturn(FailureDisposition.RETRY_SCHEDULED);

        RunResult result = worker(repository, List.of(handler)).runOnce();

        assertEquals(new RunResult(1, 0, 1, 0, 0), result);
        verify(repository, never()).markPublished(anyLong(), any(), any());
    }

    @Test
    void unknownEventTypeIsFailedInsteadOfPublished() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.unregistered");
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true);
        when(repository.markFailure(
                        eq(event.id()),
                        eq(OWNER),
                        eq(NOW),
                        eq("UNKNOWN_EVENT_TYPE"),
                        eq("No outbox handler is registered for event type alarm.unregistered"),
                        eq(INITIAL_BACKOFF),
                        eq(MAX_BACKOFF)))
                .thenReturn(FailureDisposition.DEAD_LETTERED);

        RunResult result = worker(repository, List.of()).runOnce();

        assertEquals(new RunResult(1, 0, 0, 1, 0), result);
        verify(repository, never()).markPublished(anyLong(), any(), any());
    }

    @Test
    void skipsHandlerWhenLeaseWasLostBeforeDispatch() throws Exception {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        when(handler.eventType()).thenReturn(event.eventType());
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(false);

        RunResult result = worker(repository, List.of(handler)).runOnce();

        assertEquals(new RunResult(1, 0, 0, 0, 1), result);
        verify(handler, never()).handle(any());
        verify(repository, never()).markPublished(anyLong(), any(), any());
        verify(repository, never()).markFailure(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reportsLeaseLossWhenSideEffectSucceededButPublishFenceRejectsOldOwner() throws Exception {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        when(handler.eventType()).thenReturn(event.eventType());
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true);
        when(repository.markPublished(event.id(), OWNER, NOW)).thenReturn(false);

        RunResult result = worker(repository, List.of(handler)).runOnce();

        assertEquals(new RunResult(1, 0, 0, 0, 1), result);
        verify(handler).handle(event);
    }

    @Test
    void renewsLeaseRepeatedlyWhileLongHandlerIsRunning() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        Heartbeat heartbeat = mock(Heartbeat.class);
        AtomicReference<BooleanSupplier> renewal = new AtomicReference<>();
        AtomicInteger handlerRenewals = new AtomicInteger();
        OutboxEventHandler handler = new OutboxEventHandler() {
            @Override
            public String eventType() {
                return event.eventType();
            }

            @Override
            public void handle(OutboxEvent ignored) {
                if (renewal.get().getAsBoolean()) {
                    handlerRenewals.incrementAndGet();
                }
                if (renewal.get().getAsBoolean()) {
                    handlerRenewals.incrementAndGet();
                }
            }
        };
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true);
        when(repository.markPublished(event.id(), OWNER, NOW)).thenReturn(true);

        RunResult result = worker(repository, List.of(handler), (leaseTtl, callback, threadName) -> {
                    renewal.set(callback);
                    return heartbeat;
                })
                .runOnce();

        assertEquals(new RunResult(1, 1, 0, 0, 0), result);
        assertEquals(2, handlerRenewals.get());
        verify(repository, atLeast(3)).renewLease(event.id(), OWNER, NOW, LEASE);
        verify(heartbeat).requireValid("Outbox lease was lost before publishing");
        verify(heartbeat).close();
    }

    @Test
    void heartbeatLossAfterSideEffectPreventsEveryTerminalWrite() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        Heartbeat heartbeat = mock(Heartbeat.class);
        AtomicReference<BooleanSupplier> renewal = new AtomicReference<>();
        AtomicReference<Boolean> heartbeatRenewed = new AtomicReference<>();
        OutboxEventHandler handler = new OutboxEventHandler() {
            @Override
            public String eventType() {
                return event.eventType();
            }

            @Override
            public void handle(OutboxEvent ignored) {
                heartbeatRenewed.set(renewal.get().getAsBoolean());
            }
        };
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true, false);
        doThrow(new IllegalStateException("lease expired"))
                .when(heartbeat)
                .requireValid("Outbox lease was lost before publishing");

        RunResult result = worker(repository, List.of(handler), (leaseTtl, callback, threadName) -> {
                    renewal.set(callback);
                    return heartbeat;
                })
                .runOnce();

        assertEquals(Boolean.FALSE, heartbeatRenewed.get());
        assertEquals(new RunResult(1, 0, 0, 0, 1), result);
        verify(repository, never()).markPublished(anyLong(), any(), any());
        verify(repository, never()).markFailure(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void heartbeatLossDuringFailedHandlerPreventsFailureWrite() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        Heartbeat heartbeat = mock(Heartbeat.class);
        AtomicReference<BooleanSupplier> renewal = new AtomicReference<>();
        OutboxEventHandler handler = new OutboxEventHandler() {
            @Override
            public String eventType() {
                return event.eventType();
            }

            @Override
            public void handle(OutboxEvent ignored) {
                assertFalse(renewal.get().getAsBoolean());
                throw new IllegalStateException("downstream unavailable");
            }
        };
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true, false);
        doThrow(new IllegalStateException("lease expired"))
                .when(heartbeat)
                .requireValid("Outbox lease was lost before failure recording");

        RunResult result = worker(repository, List.of(handler), (leaseTtl, callback, threadName) -> {
                    renewal.set(callback);
                    return heartbeat;
                })
                .runOnce();

        assertEquals(new RunResult(1, 0, 0, 0, 1), result);
        verify(repository, never()).markFailure(anyLong(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).markPublished(anyLong(), any(), any());
    }

    @Test
    void restoresOutboxCorrelationMdcForHandlerAndCleansWorkerScope() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        OutboxEventHandler handler = new OutboxEventHandler() {
            @Override
            public String eventType() {
                return event.eventType();
            }

            @Override
            public void handle(OutboxEvent ignored) {
                observed.set(MDC.getCopyOfContextMap());
            }
        };
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true);
        when(repository.markPublished(event.id(), OWNER, NOW)).thenReturn(true);
        MDC.put("outer", "preserved");
        try {
            worker(repository, List.of(handler)).runOnce();

            assertEquals("req_123", observed.get().get(CorrelationContext.REQUEST_ID));
            assertEquals("oevt_123", observed.get().get(CorrelationContext.EVENT_ID));
            assertEquals("alarm.acknowledged", observed.get().get(CorrelationContext.EVENT_TYPE));
            assertEquals("worker:outbox", observed.get().get(CorrelationContext.ROUTE));
            assertEquals(Map.of("outer", "preserved"), MDC.getCopyOfContextMap());
        } finally {
            MDC.clear();
        }
    }

    @Test
    void redactsSecretsBeforePersistingOutboxFailureSummary() throws Exception {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("alarm.acknowledged");
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        when(handler.eventType()).thenReturn(event.eventType());
        doThrow(new IllegalStateException(
                        "Authorization: Bearer abcdefghijklmnopqrstuvwxyz apiKey=sk-abcdefghijklmnopqrstuvwxyz"))
                .when(handler)
                .handle(event);
        when(repository.claimBatch(OWNER, NOW, LEASE, BATCH_SIZE)).thenReturn(List.of(event));
        when(repository.renewLease(event.id(), OWNER, NOW, LEASE)).thenReturn(true);
        when(repository.markFailure(
                        eq(event.id()),
                        eq(OWNER),
                        eq(NOW),
                        eq("HANDLER_FAILURE"),
                        anyString(),
                        eq(INITIAL_BACKOFF),
                        eq(MAX_BACKOFF)))
                .thenReturn(FailureDisposition.RETRY_SCHEDULED);

        worker(repository, List.of(handler)).runOnce();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(repository)
                .markFailure(
                        eq(event.id()),
                        eq(OWNER),
                        eq(NOW),
                        eq("HANDLER_FAILURE"),
                        summary.capture(),
                        eq(INITIAL_BACKOFF),
                        eq(MAX_BACKOFF));
        org.assertj.core.api.Assertions.assertThat(summary.getValue())
                .contains("Bearer [REDACTED]")
                .contains("apiKey=[REDACTED]")
                .doesNotContain("abcdefghijklmnopqrstuvwxyz")
                .doesNotContain("sk-");
    }

    private static OutboxWorker worker(OutboxRepository repository, List<? extends OutboxEventHandler> handlers) {
        return new OutboxWorker(
                repository,
                new OutboxEventHandlerRegistry(handlers),
                CLOCK,
                OWNER,
                LEASE,
                INITIAL_BACKOFF,
                MAX_BACKOFF,
                BATCH_SIZE);
    }

    private static OutboxWorker worker(
            OutboxRepository repository,
            List<? extends OutboxEventHandler> handlers,
            OutboxWorker.HeartbeatStarter heartbeatStarter) {
        return new OutboxWorker(
                repository,
                new OutboxEventHandlerRegistry(handlers),
                CLOCK,
                OWNER,
                LEASE,
                INITIAL_BACKOFF,
                MAX_BACKOFF,
                BATCH_SIZE,
                heartbeatStarter);
    }

    private static OutboxEvent event(String eventType) {
        return new OutboxEvent(
                42L,
                "oevt_123",
                "alarm",
                "alm_123",
                eventType,
                1,
                "{\"alarmId\":\"alm_123\"}",
                "req_123",
                1,
                10,
                NOW.minusSeconds(60),
                NOW.plus(LEASE));
    }

    private static final class CapturingHandler implements OutboxEventHandler {

        private final String eventType;
        private OutboxEvent handled;

        private CapturingHandler(String eventType) {
            this.eventType = eventType;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(OutboxEvent event) {
            handled = event;
        }
    }
}
