package com.kubeoncall.notification.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kubeoncall.audit.outbox.OutboxEvent;
import com.kubeoncall.notification.application.NotificationDeliveryResult;
import com.kubeoncall.notification.application.NotificationDispatcher;
import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;

class NotificationDeliveryOutboxHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

    @Test
    void recordsSuccessAndDoesNotRequestOutboxRetry() {
        Fixture fixture = fixture(NotificationDeliveryStatus.PENDING);
        NotificationDeliveryResult result = NotificationDeliveryResult.delivered(
                new com.kubeoncall.notification.application.NotificationDeliveryRequest(
                        "ndlv_1", fixture.record.message(), fixture.record.destination()),
                "feishu",
                "message-1",
                "0",
                "accepted");
        when(fixture.dispatcher.deliver(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        fixture.handler.handle(event(1, 10));

        verify(fixture.repository).markProcessing("ndlv_1", 1, NOW);
        verify(fixture.repository).markDelivered("ndlv_1", 1, result, NOW);
    }

    @Test
    void permanentProviderFailureIsTerminalWithoutOutboxRetry() {
        Fixture fixture = fixture(NotificationDeliveryStatus.PENDING);
        NotificationDeliveryResult result = failed(fixture.record, false);
        when(fixture.dispatcher.deliver(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        fixture.handler.handle(event(1, 10));

        verify(fixture.repository).markFailed("ndlv_1", 1, result, NotificationDeliveryStatus.FAILED, NOW);
    }

    @Test
    void retryableFailureUpdatesLedgerAndThrowsForOutboxBackoff() {
        Fixture fixture = fixture(NotificationDeliveryStatus.PENDING);
        NotificationDeliveryResult result = failed(fixture.record, true);
        when(fixture.dispatcher.deliver(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        assertThatThrownBy(() -> fixture.handler.handle(event(2, 10)))
                .isInstanceOf(NotificationDeliveryOutboxHandler.NotificationRetryableException.class);

        verify(fixture.repository).markFailed("ndlv_1", 2, result, NotificationDeliveryStatus.RETRYING, NOW);
    }

    @Test
    void finalRetryableFailureMirrorsOutboxDeadLetterState() {
        Fixture fixture = fixture(NotificationDeliveryStatus.RETRYING);
        NotificationDeliveryResult result = failed(fixture.record, true);
        when(fixture.dispatcher.deliver(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        assertThatThrownBy(() -> fixture.handler.handle(event(10, 10)))
                .isInstanceOf(NotificationDeliveryOutboxHandler.NotificationRetryableException.class);

        verify(fixture.repository).markFailed("ndlv_1", 10, result, NotificationDeliveryStatus.DEAD_LETTER, NOW);
    }

    @Test
    void terminalDeliveryMakesRedeliveryIdempotent() {
        Fixture fixture = fixture(NotificationDeliveryStatus.DELIVERED);

        fixture.handler.handle(event(2, 10));

        verify(fixture.dispatcher, never()).deliver(org.mockito.ArgumentMatchers.any());
        verify(fixture.repository, never())
                .markProcessing(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static Fixture fixture(NotificationDeliveryStatus status) {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationDeliveryRecord record = record(status);
        when(repository.findByPublicId("ndlv_1")).thenReturn(record);
        NotificationDeliveryOutboxHandler handler =
                new NotificationDeliveryOutboxHandler(repository, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(repository, dispatcher, handler, record);
    }

    private static NotificationDeliveryResult failed(NotificationDeliveryRecord record, boolean retryable) {
        return NotificationDeliveryResult.failed(
                new com.kubeoncall.notification.application.NotificationDeliveryRequest(
                        "ndlv_1", record.message(), record.destination()),
                "feishu",
                "PROVIDER_FAILURE",
                "provider failed",
                retryable);
    }

    private static NotificationDeliveryRecord record(NotificationDeliveryStatus status) {
        NotificationMessage message = new NotificationMessage(
                "event-1",
                "alarm.firing",
                "oncall",
                NotificationPriority.HIGH,
                "NodeDown",
                "node is unavailable",
                Map.of(),
                List.of(),
                NOW);
        NotificationDestination destination = new NotificationDestination(
                "feishu-primary", "feishu", "infra", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
        return new NotificationDeliveryRecord(
                1,
                "ndlv_1",
                "delivery-key",
                message,
                destination,
                "SEND",
                status,
                0,
                0,
                false,
                null,
                null,
                null,
                null,
                "request-1",
                null,
                NOW,
                NOW);
    }

    private static OutboxEvent event(int attempt, int maxAttempts) {
        return new OutboxEvent(
                1,
                "oevt_1",
                "NOTIFICATION_DELIVERY",
                "ndlv_1",
                "notification.delivery.requested",
                1,
                "{}",
                "request-1",
                attempt,
                maxAttempts,
                NOW,
                NOW.plusSeconds(30));
    }

    private record Fixture(
            NotificationDeliveryRepository repository,
            NotificationDispatcher dispatcher,
            NotificationDeliveryOutboxHandler handler,
            NotificationDeliveryRecord record) {}
}
