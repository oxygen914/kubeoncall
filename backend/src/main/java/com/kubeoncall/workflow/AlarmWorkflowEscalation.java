package com.kubeoncall.workflow;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.escalation.AlarmEscalationService;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Service
public class AlarmWorkflowEscalation {

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;
    private final AlarmEscalationService escalationService;

    public AlarmWorkflowEscalation(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlarmEscalationService escalationService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.escalationService = escalationService;
    }

    public void addResult(
            NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState activeState,
            String fingerprint,
            AlertWorkflowContext context) {
        AlarmSeverity severity = evaluation == null ? null : evaluation.finalSeverity();
        if (severity != AlarmSeverity.P0 && severity != AlarmSeverity.P1) {
            return;
        }
        long threshold = severity == AlarmSeverity.P0
                ? properties.getAlarm().getP0EscalationCount()
                : properties.getAlarm().getP1EscalationCount();
        long minimumDurationSeconds = severity == AlarmSeverity.P0
                ? properties.getAlarm().getP0EscalationAfterSeconds()
                : properties.getAlarm().getP1EscalationAfterSeconds();
        boolean countReached = activeState != null && activeState.count() >= Math.max(1, threshold);
        boolean durationReached = activeState != null
                && activeState.firstSeen() != null
                && !activeState
                        .firstSeen()
                        .plusSeconds(Math.max(0, minimumDurationSeconds))
                        .isAfter(java.time.Instant.now());
        if (!countReached && !durationReached) {
            return;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey("alarm-ack:" + fingerprint))) {
            return;
        }
        Boolean accepted = redisTemplate
                .opsForValue()
                .setIfAbsent(
                        "alarm-escalation:" + fingerprint,
                        severity.name(),
                        Duration.ofSeconds(Math.max(60, properties.getAlarm().getEscalationTtlSeconds())));
        if (Boolean.FALSE.equals(accepted)) {
            return;
        }
        context.addNodeResult(escalationService.escalate(event, evaluation, activeState.count(), threshold));
    }
}
