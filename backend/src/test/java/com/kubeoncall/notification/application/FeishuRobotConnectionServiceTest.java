package com.kubeoncall.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.provider.feishu.FeishuRobotRegistry;

class FeishuRobotConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");

    @Test
    void queuesAuditedConnectionVerificationUsingOnlyRobotId() {
        NotificationSubmissionService submissionService = mock(NotificationSubmissionService.class);
        OperationAuditWriter auditWriter = mock(OperationAuditWriter.class);
        when(submissionService.submitToDestination(any(), any(), anyString())).thenAnswer(invocation -> {
            NotificationMessage message = invocation.getArgument(0);
            return new NotificationPublishResult(
                    NotificationPublishResult.Status.QUEUED, message.eventId(), List.of("ndlv_1"));
        });
        FeishuRobotConnectionService service = service(Set.of("infra-primary"), submissionService, auditWriter);

        FeishuRobotConnectionService.ConnectionResult result = service.connect(command("infra-primary", null));

        assertThat(result.robotId()).isEqualTo("infra-primary");
        assertThat(result.status()).isEqualTo(NotificationPublishResult.Status.QUEUED);
        assertThat(result.deliveryIds()).containsExactly("ndlv_1");

        ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
        ArgumentCaptor<NotificationDestination> destination = ArgumentCaptor.forClass(NotificationDestination.class);
        verify(submissionService).submitToDestination(message.capture(), destination.capture(), anyString());
        assertThat(message.getValue().eventType()).isEqualTo(FeishuRobotConnectionService.CONNECTION_EVENT_TYPE);
        assertThat(message.getValue().facts()).containsEntry("机器人 ID", "infra-primary");
        assertThat(destination.getValue().target()).isEqualTo("infra-primary");
        verify(auditWriter).write(any());
    }

    @Test
    void sameIdempotencyKeyProducesSameEventId() {
        NotificationSubmissionService submissionService = mock(NotificationSubmissionService.class);
        when(submissionService.submitToDestination(any(), any(), anyString())).thenAnswer(invocation -> {
            NotificationMessage message = invocation.getArgument(0);
            return new NotificationPublishResult(
                    NotificationPublishResult.Status.QUEUED, message.eventId(), List.of("ndlv_1"));
        });
        FeishuRobotConnectionService service =
                service(Set.of("infra-primary"), submissionService, mock(OperationAuditWriter.class));

        String first =
                service.connect(command("infra-primary", "feishu-connect-0001")).eventId();
        String second =
                service.connect(command("infra-primary", "feishu-connect-0001")).eventId();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsUnknownRobotWithoutSubmitting() {
        FeishuRobotConnectionService service = service(
                Set.of("infra-primary"), mock(NotificationSubmissionService.class), mock(OperationAuditWriter.class));

        assertThatThrownBy(() -> service.connect(command("missing", null)))
                .isInstanceOf(FeishuRobotConnectionService.ConnectionException.class)
                .satisfies(
                        exception -> assertThat(((FeishuRobotConnectionService.ConnectionException) exception).code())
                                .isEqualTo(FeishuRobotConnectionService.Code.NOT_FOUND));
    }

    private static FeishuRobotConnectionService service(
            Set<String> robotIds, NotificationSubmissionService submissionService, OperationAuditWriter auditWriter) {
        return new FeishuRobotConnectionService(
                new FeishuRobotRegistry(robotIds), submissionService, auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FeishuRobotConnectionService.ConnectionCommand command(String robotId, String idempotencyKey) {
        return new FeishuRobotConnectionService.ConnectionCommand(
                robotId, idempotencyKey, 7L, "Operator", "req-feishu-connect-0001", "127.0.0.1", "test");
    }
}
