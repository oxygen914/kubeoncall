package com.kubeoncall.web;

import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.state.AlarmAcknowledgementStore;
import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;
import com.kubeoncall.alarm.recovery.AlarmRecoveryService;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.web.dto.AlarmAcknowledgementRequest;
import com.kubeoncall.web.dto.AlarmAcknowledgementResponse;
import com.kubeoncall.web.dto.AlarmRecoveryConfirmationRequest;
import com.kubeoncall.web.dto.AlarmRecoveryConfirmationResponse;
import com.kubeoncall.workflow.AlertWorkflowService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmControllerAcknowledgementTest {

    @Test
    void shouldAcknowledgeAlarmAndWriteAudit() {
        AlarmAcknowledgementStore acknowledgementStore = mock(AlarmAcknowledgementStore.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        Instant acknowledgedAt = Instant.now();
        Instant expiresAt = acknowledgedAt.plusSeconds(600);
        when(acknowledgementStore.acknowledge(eq("fp-api"), eq("oncall-user"), eq("investigating"), eq(Duration.ofSeconds(600))))
                .thenReturn(new AlarmAcknowledgementStore.AlarmAcknowledgement(
                        "fp-api", "oncall-user", "investigating", acknowledgedAt, expiresAt));
        when(acknowledgementStore.keyFor("fp-api")).thenReturn("alarm-ack:fp-api");
        AlarmController controller = new AlarmController(
                mock(AlarmNormalizer.class),
                mock(AlertWorkflowService.class),
                mock(AlarmSilenceApprovalStore.class),
                acknowledgementStore,
                properties,
                metricsService,
                auditService,
                mock(AlarmRecoveryService.class)
        );

        AlarmAcknowledgementResponse response = controller.acknowledge(
                new AlarmAcknowledgementRequest("fp-api", "oncall-user", "investigating", 600));

        assertTrue(response.acknowledged());
        assertEquals("alarm-ack:fp-api", response.acknowledgementKey());
        assertEquals(expiresAt, response.expiresAt());
        verify(metricsService).recordAlarmAcknowledgement("acknowledged");
        verify(auditService).recordAlarmExecution(
                eq("alarm-ack-fp-api"),
                eq("ACKNOWLEDGED"),
                anyBoolean(),
                anyBoolean(),
                anyString(),
                any(),
                eq(List.of("alarm.acknowledge")),
                any(Instant.class),
                any()
        );
    }

    @Test
    void shouldConfirmManualRecoveryWithHealthEvidence() {
        AlarmRecoveryService recoveryService = mock(AlarmRecoveryService.class);
        Instant candidateAt = Instant.now().minusSeconds(900);
        Instant confirmedAt = Instant.now();
        when(recoveryService.confirmManual("fp-recovery-api", "incident-commander", true, "healthy"))
                .thenReturn(new AlarmRecoveryState(
                        "fp-recovery-api", "alarm-api", AlarmSeverity.P0, "policy-p0", "healthy for 10m",
                        candidateAt, candidateAt.plusSeconds(600), true, AlarmRecoveryState.CONFIRMED,
                        "incident-commander", true, "healthy", confirmedAt));
        AlarmController controller = new AlarmController(
                mock(AlarmNormalizer.class), mock(AlertWorkflowService.class), mock(AlarmSilenceApprovalStore.class),
                mock(AlarmAcknowledgementStore.class), new KubeOnCallProperties(), mock(KubeOnCallMetricsService.class),
                mock(ExecutionAuditService.class), recoveryService);

        AlarmRecoveryConfirmationResponse response = controller.confirmRecovery(
                new AlarmRecoveryConfirmationRequest("fp-recovery-api", "incident-commander", true, "healthy"));

        assertEquals(AlarmRecoveryState.CONFIRMED, response.status());
        assertEquals("P0", response.severity());
        assertTrue(response.healthCheckPassed());
        assertEquals(confirmedAt, response.confirmedAt());
    }
}
