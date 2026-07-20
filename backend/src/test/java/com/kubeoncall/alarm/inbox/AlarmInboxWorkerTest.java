package com.kubeoncall.alarm.inbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmLifecycleGuard;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.ingest.AlarmPayload;
import com.kubeoncall.alarm.ingest.InboundAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.workflow.AlertWorkflowService;

class AlarmInboxWorkerTest {

    @Test
    void shouldCompleteLifecycleAndAcknowledgeOwnedClaim() {
        Fixture fixture = new Fixture();
        when(fixture.inbox.renew(fixture.claimed)).thenReturn(true);
        when(fixture.lifecycleGuard.renew(fixture.reservation)).thenReturn(true);

        fixture.worker.process(fixture.claimed);

        verify(fixture.workflowService).process(fixture.normalized);
        verify(fixture.lifecycleGuard).complete(fixture.reservation, fixture.normalized);
        verify(fixture.inbox).acknowledge(fixture.claimed);
        verify(fixture.metricsService).recordAlarmInbox("processed");
    }

    @Test
    void shouldStopFinalizationAfterClaimOwnershipIsLost() {
        Fixture fixture = new Fixture();
        when(fixture.inbox.renew(fixture.claimed)).thenReturn(false);

        fixture.worker.process(fixture.claimed);

        verify(fixture.workflowService).process(fixture.normalized);
        verify(fixture.lifecycleGuard, never()).complete(fixture.reservation, fixture.normalized);
        verify(fixture.inbox, never()).acknowledge(fixture.claimed);
        verify(fixture.metricsService).recordAlarmInbox("claim_lost");
    }

    private static final class Fixture {
        private final AlarmEventInbox inbox = mock(AlarmEventInbox.class);
        private final AlarmNormalizer normalizer = mock(AlarmNormalizer.class);
        private final AlarmLifecycleGuard lifecycleGuard = mock(AlarmLifecycleGuard.class);
        private final AlertWorkflowService workflowService = mock(AlertWorkflowService.class);
        private final KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        private final KubeOnCallProperties properties = new KubeOnCallProperties();
        private final NormalizedAlarmEvent normalized = normalized();
        private final AlarmLifecycleGuard.Reservation reservation =
                new AlarmLifecycleGuard.Reservation("fingerprint-1", "lease-1", Duration.ofMinutes(2));
        private final AlarmEventInbox.ClaimedAlarmEvent claimed =
                new AlarmEventInbox.ClaimedAlarmEvent("1-0", inbound(), "worker-a");
        private final AlarmInboxWorker worker;

        private Fixture() {
            when(normalizer.normalize(claimed.event().alarm())).thenReturn(normalized);
            when(lifecycleGuard.reserve(normalized)).thenReturn(Optional.of(reservation));
            worker = new AlarmInboxWorker(
                    inbox, normalizer, lifecycleGuard, workflowService, properties, metricsService);
        }
    }

    private static InboundAlarmEvent inbound() {
        AlarmPayload alarm = new AlarmPayload(
                "alarm-1",
                "fingerprint-1",
                "alertmanager",
                "P1",
                "node-a",
                "node down",
                Instant.parse("2026-07-17T10:00:00Z"),
                Map.of(),
                "fingerprint-1",
                "NodeDown",
                "node",
                "node-a",
                "cluster-a",
                null,
                null,
                "prometheus.up",
                null,
                null,
                null,
                null,
                Map.of("instance", "node-a:9100"),
                Map.of(),
                "runbook-node-down",
                "firing");
        return new InboundAlarmEvent("event-1", "batch-1", "delivery-1", "alertmanager", Instant.now(), 0, alarm);
    }

    private static NormalizedAlarmEvent normalized() {
        return new NormalizedAlarmEvent(
                "alarm-1",
                "fingerprint-1",
                "NodeDown",
                "alertmanager",
                "P1",
                null,
                null,
                "node-a",
                "cluster-a",
                null,
                null,
                "prometheus.up",
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                "runbook-node-down",
                AlarmStatus.FIRING,
                Instant.parse("2026-07-17T10:00:00Z"),
                "node down",
                Map.of());
    }
}
