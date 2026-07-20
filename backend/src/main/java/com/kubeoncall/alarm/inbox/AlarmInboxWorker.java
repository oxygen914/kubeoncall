package com.kubeoncall.alarm.inbox;

import java.time.Duration;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmLifecycleGuard;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.common.concurrent.LeaseHeartbeat;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.workflow.AlertWorkflowService;

/** Drains the durable inbox and retries failures without making Alertmanager wait for workflows. */
@Service
public class AlarmInboxWorker {

    private final AlarmEventInbox inbox;
    private final AlarmNormalizer alarmNormalizer;
    private final AlarmLifecycleGuard lifecycleGuard;
    private final AlertWorkflowService alertWorkflowService;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;

    public AlarmInboxWorker(
            AlarmEventInbox inbox,
            AlarmNormalizer alarmNormalizer,
            AlarmLifecycleGuard lifecycleGuard,
            AlertWorkflowService alertWorkflowService,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService) {
        this.inbox = inbox;
        this.alarmNormalizer = alarmNormalizer;
        this.lifecycleGuard = lifecycleGuard;
        this.alertWorkflowService = alertWorkflowService;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.alarm.inbox-worker-block-millis:1000}")
    public void drain() {
        if (!properties.getAlarm().isInboxWorkerEnabled()) {
            return;
        }
        List<AlarmEventInbox.ClaimedAlarmEvent> events;
        try {
            events = inbox.claim(
                    "alarm-worker-" + Integer.toHexString(System.identityHashCode(this)),
                    properties.getAlarm().getInboxWorkerBatchSize(),
                    Duration.ofMillis(Math.max(1, properties.getAlarm().getInboxWorkerBlockMillis())));
        } catch (AlarmInboxUnavailableException ex) {
            metricsService.recordAlarmInbox("unavailable");
            return;
        }
        for (AlarmEventInbox.ClaimedAlarmEvent event : events) {
            process(event);
        }
    }

    void process(AlarmEventInbox.ClaimedAlarmEvent claimed) {
        AlarmLifecycleGuard.Reservation reservation = null;
        LeaseHeartbeat heartbeat = null;
        try {
            NormalizedAlarmEvent event =
                    alarmNormalizer.normalize(claimed.event().alarm());
            reservation = lifecycleGuard.reserve(event).orElse(null);
            if (reservation == null) {
                inbox.acknowledge(claimed);
                metricsService.recordAlarmInbox("out_of_order");
                return;
            }
            AlarmLifecycleGuard.Reservation activeReservation = reservation;
            heartbeat = LeaseHeartbeat.start(
                    claimLeaseTtl(),
                    () -> inbox.renew(claimed) && lifecycleGuard.renew(activeReservation),
                    "alarm-inbox-lease-heartbeat");
            alertWorkflowService.process(event);
            if (!heartbeat.isValid() || !inbox.renew(claimed) || !lifecycleGuard.renew(reservation)) {
                metricsService.recordAlarmInbox("claim_lost");
                return;
            }
            lifecycleGuard.complete(reservation, event);
            reservation = null;
            inbox.acknowledge(claimed);
            metricsService.recordAlarmInbox("processed");
        } catch (RuntimeException ex) {
            if (claimed.event().attempt() >= properties.getAlarm().getInboxMaxRetries()) {
                inbox.deadLetter(claimed, safeMessage(ex));
                metricsService.recordAlarmInbox("dead_letter");
                return;
            }
            inbox.retry(claimed, safeMessage(ex));
            metricsService.recordAlarmInbox("retry");
        } finally {
            if (heartbeat != null) {
                heartbeat.close();
            }
            if (reservation != null) {
                lifecycleGuard.release(reservation);
            }
        }
    }

    private Duration claimLeaseTtl() {
        return Duration.ofMillis(Math.max(1000L, properties.getAlarm().getInboxPendingClaimIdleMillis()));
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
