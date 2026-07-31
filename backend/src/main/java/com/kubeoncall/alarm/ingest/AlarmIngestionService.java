package com.kubeoncall.alarm.ingest;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.inbox.AlarmEventInbox;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerAlarmMapper;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerAlertDto;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerWebhookRequest;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerWebhookResponse;
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Accepts alarms only after they have a durable inbox record, decoupling webhook latency from workflow execution. */
@Service
public class AlarmIngestionService {

    private final AlertmanagerAlarmMapper alertmanagerMapper;
    private final AlarmNormalizer alarmNormalizer;
    private final AlarmDeliveryIdentityService deliveryIdentityService;
    private final AlarmEventInbox inbox;
    private final KubeOnCallMetricsService metricsService;

    public AlarmIngestionService(
            AlertmanagerAlarmMapper alertmanagerMapper,
            AlarmNormalizer alarmNormalizer,
            AlarmDeliveryIdentityService deliveryIdentityService,
            AlarmEventInbox inbox,
            KubeOnCallMetricsService metricsService) {
        this.alertmanagerMapper = alertmanagerMapper;
        this.alarmNormalizer = alarmNormalizer;
        this.deliveryIdentityService = deliveryIdentityService;
        this.inbox = inbox;
        this.metricsService = metricsService;
    }

    public AlertmanagerWebhookResponse acceptAlertmanager(AlertmanagerWebhookRequest request) {
        String batchId = UUID.randomUUID().toString();
        int accepted = 0;
        int duplicates = 0;
        for (AlertmanagerAlertDto alert : request.alerts()) {
            AlarmPayload alarm = alertmanagerMapper.map(request, alert);
            NormalizedAlarmEvent normalized = alarmNormalizer.normalize(alarm);
            String deliveryKey = deliveryIdentityService.deliveryKey(normalized, alert.endsAt());
            InboundAlarmEvent event = new InboundAlarmEvent(
                    UUID.randomUUID().toString(), batchId, deliveryKey, "alertmanager", Instant.now(), 0, alarm);
            AlarmEventInbox.EnqueueResult result = inbox.enqueue(event);
            if (result.duplicate()) {
                duplicates++;
                metricsService.recordAlarmInbox("duplicate");
                metricsService.recordAlarmQuality("duplicate");
            } else if (result.accepted()) {
                accepted++;
                metricsService.recordAlarmInbox("accepted");
                metricsService.recordAlarmQuality("accepted");
            } else {
                metricsService.recordAlarmInbox("rejected");
                metricsService.recordAlarmQuality("rejected");
                throw new AlarmIngestionRejectedException("Alarm inbox did not accept event");
            }
        }
        return new AlertmanagerWebhookResponse(accepted, duplicates, 0, batchId);
    }
}
