package com.kubeoncall.alarm.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.inbox.AlarmEventInbox;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerAlarmMapper;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerAlertDto;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerWebhookRequest;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerWebhookResponse;
import com.kubeoncall.service.KubeOnCallMetricsService;

class AlarmIngestionServiceTest {

    @Test
    void shouldReportAcceptedAndDuplicateDeliveriesSeparately() {
        AlertmanagerAlarmMapper mapper = mock(AlertmanagerAlarmMapper.class);
        AlarmNormalizer normalizer = mock(AlarmNormalizer.class);
        AlarmDeliveryIdentityService identityService = mock(AlarmDeliveryIdentityService.class);
        AlarmEventInbox inbox = mock(AlarmEventInbox.class);
        KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        when(mapper.map(any(), any())).thenCallRealMethod();
        when(normalizer.normalize(any())).thenReturn(event());
        when(identityService.deliveryKey(any(), any())).thenReturn("delivery-key");
        when(inbox.enqueue(any()))
                .thenReturn(new AlarmEventInbox.EnqueueResult(true, false, "event-1"))
                .thenReturn(new AlarmEventInbox.EnqueueResult(false, true, "event-2"));
        AlarmIngestionService service = new AlarmIngestionService(mapper, normalizer, identityService, inbox, metrics);

        AlertmanagerWebhookResponse response = service.acceptAlertmanager(request());

        assertEquals(1, response.accepted());
        assertEquals(1, response.duplicates());
        verify(inbox, org.mockito.Mockito.times(2)).enqueue(any());
        verify(metrics).recordAlarmInbox("accepted");
        verify(metrics).recordAlarmInbox("duplicate");
    }

    private static AlertmanagerWebhookRequest request() {
        AlertmanagerAlertDto alert = new AlertmanagerAlertDto(
                "firing",
                Map.of("alertname", "ApiErrors", "severity", "warning"),
                Map.of(),
                Instant.parse("2026-07-15T00:00:00Z"),
                null,
                null,
                "fp-1");
        return new AlertmanagerWebhookRequest(
                "4", "group", 0, "firing", "oncall", Map.of(), Map.of(), Map.of(), null, List.of(alert, alert));
    }

    private static NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "fp-1",
                "fp-1",
                "ApiErrors",
                "alertmanager",
                "warning",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                Instant.now(),
                null,
                Map.of());
    }
}
