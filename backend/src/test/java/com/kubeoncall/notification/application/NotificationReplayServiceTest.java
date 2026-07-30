package com.kubeoncall.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.mockito.ArgumentCaptor;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.notification.delivery.NotificationDeliveryRecord;
import com.kubeoncall.notification.delivery.NotificationDeliveryRepository;
import com.kubeoncall.notification.delivery.NotificationDeliveryStatus;
import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;

class NotificationReplayServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

    @Test
    void requeuesFailedDeliveryAndWritesNewOutboxEventAndAudit() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        OperationAuditWriter auditWriter = mock(OperationAuditWriter.class);
        NotificationDeliveryRecord delivery = delivery(NotificationDeliveryStatus.DEAD_LETTER, 2);
        when(repository.findByPublicId("ndlv_1")).thenReturn(delivery);
        when(repository.requeue("ndlv_1", NotificationDeliveryStatus.DEAD_LETTER, NOW))
                .thenReturn(true);
        NotificationReplayService service =
                new NotificationReplayService(repository, outboxWriter, auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));

        NotificationReplayService.ReplayResult result = service.replay(command());

        assertThat(result.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(result.replayCount()).isEqualTo(3);
        ArgumentCaptor<OutboxWriter.OutboxEvent> event = ArgumentCaptor.forClass(OutboxWriter.OutboxEvent.class);
        verify(outboxWriter).enqueue(event.capture());
        assertThat(event.getValue().aggregatePublicId()).isEqualTo("ndlv_1");
        assertThat(event.getValue().payload()).containsEntry("manualReplay", true);
        verify(auditWriter).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesReplayWhenDeliveryIsNotTerminalFailure() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        OperationAuditWriter auditWriter = mock(OperationAuditWriter.class);
        when(repository.findByPublicId("ndlv_1")).thenReturn(delivery(NotificationDeliveryStatus.DELIVERED, 0));
        NotificationReplayService service =
                new NotificationReplayService(repository, outboxWriter, auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.replay(command()))
                .isInstanceOfSatisfying(
                        NotificationReplayService.ReplayException.class,
                        exception -> assertThat(exception.code()).isEqualTo(NotificationReplayService.Code.CONFLICT));

        verify(outboxWriter, never()).enqueue(org.mockito.ArgumentMatchers.any());
        verify(auditWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    private static NotificationReplayService.ReplayCommand command() {
        return new NotificationReplayService.ReplayCommand(
                "ndlv_1", 7L, "operator", "retry after credential rotation", "request-1", "127.0.0.1", "test");
    }

    private static NotificationDeliveryRecord delivery(NotificationDeliveryStatus status, int replayCount) {
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
                10,
                replayCount,
                true,
                null,
                null,
                "PROVIDER_FAILURE",
                "failed",
                "request-1",
                null,
                NOW,
                NOW);
    }
}
