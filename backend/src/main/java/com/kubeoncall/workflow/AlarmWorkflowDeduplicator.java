package com.kubeoncall.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

/** Claims a fingerprint for the configured deduplication window and reports duplicate alarms. */
@Service
public class AlarmWorkflowDeduplicator {

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;
    private final AlarmWorkflowAuditRecorder auditRecorder;

    public AlarmWorkflowDeduplicator(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlarmWorkflowAuditRecorder auditRecorder) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.auditRecorder = auditRecorder;
    }

    public Optional<List<NodeResult>> handle(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            Instant startedAt) {
        String fingerprint = preparedAlarm.fingerprint();
        Boolean accepted = redisTemplate
                .opsForValue()
                .setIfAbsent(
                        "alarm-dedup:" + fingerprint,
                        preparedAlarm.event().alarmId() == null
                                ? fingerprint
                                : preparedAlarm.event().alarmId(),
                        dedupTtl(preparedAlarm.evaluation().finalSeverity()));
        if (!Boolean.FALSE.equals(accepted)) {
            return Optional.empty();
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("fingerprint", fingerprint);
        payload.put("dedupKey", fingerprint);
        payload.put("dedupHit", true);
        payload.put("activeAlarm", auditRecorder.activeAlarmPayload(preparedAlarm.activeState()));
        NodeResult result = new NodeResult("alarmDedup", NodeStatus.FAILURE, "Duplicate alarm ignored", payload);
        auditRecorder.recordTerminal(
                preparedAlarm,
                memoryRecall,
                result,
                "DEDUP_HIT",
                true,
                false,
                "Duplicate alarm ignored: fingerprint=" + fingerprint,
                List.of("alarm.dedup", "alarm.dedup.hit"),
                startedAt,
                Map.of("dedupHit", true));
        return Optional.of(List.of(result));
    }

    private Duration dedupTtl(AlarmSeverity severity) {
        long seconds =
                switch (severity == null ? AlarmSeverity.P3 : severity) {
                    case P0 -> properties.getAlarm().getP0DedupTtlSeconds();
                    case P1 -> properties.getAlarm().getP1DedupTtlSeconds();
                    case P2 -> properties.getAlarm().getP2DedupTtlSeconds();
                    case P3 -> properties.getAlarm().getP3DedupTtlSeconds();
                    case INFO -> properties.getAlarm().getInfoDedupTtlSeconds();
                };
        if (seconds <= 0) {
            seconds = properties.getWorkflow().getAlarmDedupTtlSeconds();
        }
        return Duration.ofSeconds(seconds);
    }
}
