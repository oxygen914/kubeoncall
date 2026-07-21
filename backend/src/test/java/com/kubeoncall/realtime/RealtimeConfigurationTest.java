package com.kubeoncall.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.outbox.OutboxEventHandler;

class RealtimeConfigurationTest {

    @Test
    void registersEveryOutboxEventTypeCurrentlyProducedByCoreWorkflows() {
        RealtimeConfiguration configuration = new RealtimeConfiguration();
        ObjectMapper objectMapper = new ObjectMapper();
        RealtimeEventHub hub = new RealtimeEventHub(broker(), emptyTransportProvider());

        List<OutboxEventHandler> handlers = List.of(
                configuration.alarmAcknowledgedRealtimeHandler(objectMapper, hub),
                configuration.alarmRecoveryConfirmedRealtimeHandler(objectMapper, hub),
                configuration.alarmSilenceApprovedRealtimeHandler(objectMapper, hub),
                configuration.approvalDecidedRealtimeHandler(objectMapper, hub),
                configuration.approvalRequestedRealtimeHandler(objectMapper, hub),
                configuration.executionCreatedRealtimeHandler(objectMapper, hub),
                configuration.executionUpdatedRealtimeHandler(objectMapper, hub),
                configuration.taskCreatedRealtimeHandler(objectMapper, hub),
                configuration.taskUpdatedRealtimeHandler(objectMapper, hub));

        assertThat(handlers)
                .extracting(OutboxEventHandler::eventType)
                .containsExactlyInAnyOrder(
                        "alarm.acknowledged",
                        "alarm.recovery.confirmed",
                        "alarm.silence.approved",
                        "approval.decided",
                        "approval.requested",
                        "execution.created",
                        "execution.updated",
                        "task.created",
                        "task.updated")
                .doesNotHaveDuplicates();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RealtimeClusterTransport> emptyTransportProvider() {
        ObjectProvider<RealtimeClusterTransport> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static RealtimeEventBroker broker() {
        return new InMemoryRealtimeEventBroker(
                10, Duration.ofMinutes(10), Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
    }
}
