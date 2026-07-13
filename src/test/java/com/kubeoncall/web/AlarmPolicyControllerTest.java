package com.kubeoncall.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.web.dto.AlarmPolicyReplayRequest;
import com.kubeoncall.web.dto.AlarmPolicyReplayResponse;

class AlarmPolicyControllerTest {

    @Test
    void shouldAuditEmptyReplay() {
        YamlAlarmPolicyRepository repository = mock(YamlAlarmPolicyRepository.class);
        when(repository.activeVersion()).thenReturn("v1");
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        AlarmPolicyController controller = new AlarmPolicyController(
                repository, mock(AlarmNormalizer.class), mock(AlarmPolicyEngine.class), audit);

        AlarmPolicyReplayResponse result = controller.replay(new AlarmPolicyReplayRequest(List.of()));

        assertEquals(0, result.total());
        verify(audit)
                .recordAlarmExecution(
                        any(),
                        eq("POLICY_REPLAYED"),
                        eq(true),
                        eq(false),
                        any(),
                        any(),
                        any(),
                        any(Instant.class),
                        any(Map.class));
    }

    @Test
    void shouldUseStableAuditFailureReasonForDryRun() {
        YamlAlarmPolicyRepository repository = mock(YamlAlarmPolicyRepository.class);
        AlarmNormalizer normalizer = mock(AlarmNormalizer.class);
        when(normalizer.normalize(null)).thenThrow(new IllegalStateException("upstream credential rejected"));
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        AlarmPolicyController controller =
                new AlarmPolicyController(repository, normalizer, mock(AlarmPolicyEngine.class), audit);

        assertThrows(IllegalStateException.class, () -> controller.dryRun(null));

        ArgumentCaptor<String> failureReason = ArgumentCaptor.forClass(String.class);
        verify(audit)
                .recordAlarmExecution(
                        any(),
                        eq("FAILED"),
                        eq(true),
                        eq(false),
                        any(),
                        failureReason.capture(),
                        any(),
                        any(Instant.class),
                        any(Map.class));
        assertEquals("Alarm policy evaluation failed", failureReason.getValue());
    }
}
