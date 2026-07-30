package com.kubeoncall.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.notification.delivery.NotificationDeliveryRecord;
import com.kubeoncall.notification.delivery.NotificationDeliveryRepository;
import com.kubeoncall.notification.delivery.NotificationDeliveryStatus;
import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;
import com.kubeoncall.notification.domain.NotificationRoute;

class NotificationSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

    @Test
    void persistsAndEnqueuesOneOutboxEventPerNewDestination() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        NotificationDestination first = destination("feishu-primary", "feishu");
        NotificationDestination second = destination("dingtalk-backup", "dingtalk");
        NotificationMessage message = message();
        when(repository.createIfAbsent(anyString(), anyString(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String publicId = invocation.getArgument(0);
                    String deliveryKey = invocation.getArgument(1);
                    NotificationDestination destination = invocation.getArgument(3);
                    return new NotificationDeliveryRepository.CreateResult(
                            true, record(publicId, deliveryKey, message, destination));
                });
        NotificationSubmissionService service = new NotificationSubmissionService(
                candidate -> java.util.Optional.of(new NotificationRoute("oncall", List.of(first, second))),
                repository,
                outboxWriter,
                Clock.fixed(NOW, ZoneOffset.UTC));

        NotificationPublishResult result = service.submit(message, "request-1");

        assertThat(result.status()).isEqualTo(NotificationPublishResult.Status.QUEUED);
        assertThat(result.deliveryIds()).hasSize(2);
        ArgumentCaptor<OutboxWriter.OutboxEvent> events = ArgumentCaptor.forClass(OutboxWriter.OutboxEvent.class);
        verify(outboxWriter, org.mockito.Mockito.times(2)).enqueue(events.capture());
        assertThat(events.getAllValues())
                .allMatch(event -> event.eventType().equals(NotificationSubmissionService.DELIVERY_REQUESTED_EVENT));
        assertThat(events.getAllValues())
                .extracting(OutboxWriter.OutboxEvent::aggregatePublicId)
                .containsExactlyElementsOf(result.deliveryIds());
    }

    @Test
    void duplicateSubmissionDoesNotEnqueueAnotherOutboxEvent() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        NotificationDestination destination = destination("feishu-primary", "feishu");
        NotificationMessage message = message();
        when(repository.createIfAbsent(anyString(), anyString(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> new NotificationDeliveryRepository.CreateResult(
                        false, record(invocation.getArgument(0), invocation.getArgument(1), message, destination)));
        NotificationSubmissionService service = new NotificationSubmissionService(
                candidate -> java.util.Optional.of(new NotificationRoute("oncall", List.of(destination))),
                repository,
                outboxWriter,
                Clock.fixed(NOW, ZoneOffset.UTC));

        NotificationPublishResult result = service.submit(message, "request-1");

        assertThat(result.status()).isEqualTo(NotificationPublishResult.Status.ALREADY_QUEUED);
        verify(outboxWriter, never()).enqueue(any());
    }

    private static NotificationDeliveryRecord record(
            String publicId, String deliveryKey, NotificationMessage message, NotificationDestination destination) {
        return new NotificationDeliveryRecord(
                1,
                publicId,
                deliveryKey,
                message,
                destination,
                "SEND",
                NotificationDeliveryStatus.PENDING,
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

    private static NotificationMessage message() {
        return new NotificationMessage(
                "event-1",
                "alarm.firing",
                "oncall",
                NotificationPriority.HIGH,
                "NodeDown",
                "node is unavailable",
                Map.of(),
                List.of(),
                NOW);
    }

    private static NotificationDestination destination(String id, String provider) {
        return new NotificationDestination(
                id, provider, id + "-target", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
    }
}
