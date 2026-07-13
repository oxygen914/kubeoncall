package com.kubeoncall.workflow;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Service
public class AlarmNodeNoiseSuppression {

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;

    public AlarmNodeNoiseSuppression(StringRedisTemplate redisTemplate, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void recordSource(NormalizedAlarmEvent event) {
        if (!isNodeNotReady(event)
                || event.resourceName() == null
                || event.resourceName().isBlank()) {
            return;
        }
        String key = nodeSuppressionKey(event.cluster(), event.resourceName());
        if (event.status() == AlarmStatus.RESOLVED) {
            redisTemplate.delete(key);
            return;
        }
        redisTemplate
                .opsForValue()
                .set(
                        key,
                        event.fingerprint() == null ? "NodeNotReady" : event.fingerprint(),
                        Duration.ofSeconds(Math.max(60, properties.getAlarm().getNodeNotReadySuppressionTtlSeconds())));
    }

    public Decision evaluate(NormalizedAlarmEvent event) {
        if (event.resourceType() != AlarmResourceType.POD) {
            return Decision.none();
        }
        String nodeName = nodeName(event);
        if (nodeName == null) {
            return Decision.none();
        }
        String key = nodeSuppressionKey(event.cluster(), nodeName);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return Decision.none();
        }
        return new Decision(true, nodeName, key, "NodeNotReady is active for node " + nodeName);
    }

    private boolean isNodeNotReady(NormalizedAlarmEvent event) {
        String alertName = event.alertName() == null ? "" : event.alertName();
        return event.resourceType() == AlarmResourceType.NODE
                && alertName.toLowerCase().contains("nodenotready");
    }

    private String nodeName(NormalizedAlarmEvent event) {
        String value = stringValue(event.labels().get("node"));
        if (value == null) {
            value = stringValue(event.labels().get("nodeName"));
        }
        if (value == null) {
            value = stringValue(event.labels().get("kubernetes.io/hostname"));
        }
        if (value == null) {
            value = stringValue(event.annotations().get("node"));
        }
        if (value == null) {
            value = stringValue(event.metadata().get("node"));
        }
        if (value == null) {
            value = stringValue(event.metadata().get("nodeName"));
        }
        return value;
    }

    private String nodeSuppressionKey(String cluster, String nodeName) {
        String normalizedCluster = cluster == null || cluster.isBlank() ? "default" : cluster;
        return "alarm-suppression:node:" + normalizedCluster + ":" + nodeName;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    public record Decision(boolean suppressed, String nodeName, String suppressionKey, String reason) {

        private static Decision none() {
            return new Decision(false, null, null, "");
        }
    }
}
