package com.kubeoncall.web;

import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.web.dto.AlarmPolicyReplayRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmPolicyControllerTest {

    @Test
    void shouldAuditEmptyReplay() {
        YamlAlarmPolicyRepository repository = mock(YamlAlarmPolicyRepository.class);
        when(repository.activeVersion()).thenReturn("v1");
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        AlarmPolicyController controller = new AlarmPolicyController(
                repository, mock(AlarmNormalizer.class), mock(AlarmPolicyEngine.class), audit);

        Map<String, Object> result = controller.replay(new AlarmPolicyReplayRequest(List.of()));

        assertEquals(0, result.get("total"));
        verify(audit).recordAlarmExecution(
                any(), eq("POLICY_REPLAYED"), eq(true), eq(false), any(), any(), any(),
                any(Instant.class), any(Map.class));
    }
}
