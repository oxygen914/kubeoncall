package com.kubeoncall.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.escalation.AlarmEscalationService;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

class AlarmWorkflowEscalationTest {

    @Test
    void shouldAppendEscalationResultWhenP0ReachesThreshold() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.hasKey("alarm-ack:fp-p0")).thenReturn(false);
        when(values.setIfAbsent(eq("alarm-escalation:fp-p0"), eq("P0"), any())).thenReturn(true);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setP0EscalationCount(2);
        AlarmEscalationService escalationService = mock(AlarmEscalationService.class);
        when(escalationService.escalate(any(), any(), eq(2L), eq(2L)))
                .thenReturn(new NodeResult("alarmEscalation", NodeStatus.SUCCESS, "delivered", Map.of()));
        AlarmWorkflowEscalation escalation = new AlarmWorkflowEscalation(redisTemplate, properties, escalationService);
        AlertWorkflowContext context = new AlertWorkflowContext(
                legacyEvent(), event(), AlarmEvaluationResult.unmatched(AlarmSeverity.P0, "critical"), Instant.now());

        escalation.addResult(
                event(),
                AlarmEvaluationResult.unmatched(AlarmSeverity.P0, "critical"),
                activeState(),
                "fp-p0",
                context);

        assertEquals("alarmEscalation", context.getNodeResults().get(0).nodeName());
        verify(escalationService).escalate(any(), any(), eq(2L), eq(2L));
    }

    private ActiveAlarmState activeState() {
        return new ActiveAlarmState(
                "fp-p0",
                "alarm-p0",
                "HostHighCpuUsageP0",
                "cluster-a",
                "prod",
                "infra",
                "node-a",
                AlarmSeverity.P0,
                AlarmStatus.FIRING,
                "policy-p0",
                Instant.now().minusSeconds(60),
                Instant.now(),
                2);
    }

    private NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "alarm-p0",
                "fp-p0",
                "HostHighCpuUsageP0",
                "prometheus",
                "critical",
                AlarmSeverity.P0,
                AlarmResourceType.NODE,
                "node-a",
                "cluster-a",
                "prod",
                "infra",
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
                "cpu high",
                Map.of());
    }

    private com.kubeoncall.domain.alarm.AlarmEvent legacyEvent() {
        return new com.kubeoncall.domain.alarm.AlarmEvent(
                "alarm-p0", "fp-p0", "prometheus", "P0", "node-a", "cpu high", Instant.now(), Map.of());
    }
}
