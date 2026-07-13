package com.kubeoncall.alarm.state;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Service
public class ActiveAlarmStore {

    private static final Logger log = LoggerFactory.getLogger(ActiveAlarmStore.class);
    private static final String KEY_PREFIX = "alarm-active:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public ActiveAlarmStore(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ActiveAlarmState record(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation, String fingerprint) {
        Instant now = Instant.now();
        Instant eventTime = event.occurredAt() == null ? now : event.occurredAt();
        ActiveAlarmState previous = find(fingerprint).orElse(null);
        AlarmStatus status = event.status() == null ? AlarmStatus.FIRING : event.status();
        AlarmSeverity severity = status == AlarmStatus.RESOLVED && previous != null
                ? previous.severity()
                : evaluation != null && evaluation.finalSeverity() != null
                        ? evaluation.finalSeverity()
                        : event.severity();
        String policyId = status == AlarmStatus.RESOLVED && previous != null
                ? previous.policyId()
                : evaluation == null ? null : evaluation.policyId();

        ActiveAlarmState next = new ActiveAlarmState(
                fingerprint,
                event.alarmId(),
                event.alertName(),
                event.cluster(),
                event.namespace(),
                event.service(),
                event.resourceName(),
                severity,
                status,
                policyId,
                previous == null ? eventTime : previous.firstSeen(),
                eventTime,
                previous == null ? 1 : status == AlarmStatus.RESOLVED ? previous.count() : previous.count() + 1);
        write(next, status);
        return next;
    }

    public String keyFor(String fingerprint) {
        return KEY_PREFIX + fingerprint;
    }

    public Optional<ActiveAlarmState> find(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(keyFor(fingerprint));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(raw, MAP_TYPE);
            return Optional.of(new ActiveAlarmState(
                    string(map.get("fingerprint")),
                    string(map.get("alarmId")),
                    string(map.get("alertName")),
                    string(map.get("cluster")),
                    string(map.get("namespace")),
                    string(map.get("service")),
                    string(map.get("resourceName")),
                    severity(map.get("severity")),
                    status(map.get("status")),
                    string(map.get("policyId")),
                    instant(map.get("firstSeen")),
                    instant(map.get("lastSeen")),
                    number(map.get("count"))));
        } catch (Exception ex) {
            log.warn(
                    "Unable to parse cached active alarm state; treating it as absent: errorType={}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void write(ActiveAlarmState state, AlarmStatus status) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fingerprint", state.fingerprint());
            map.put("alarmId", state.alarmId());
            map.put("alertName", state.alertName());
            map.put("cluster", state.cluster());
            map.put("namespace", state.namespace());
            map.put("service", state.service());
            map.put("resourceName", state.resourceName());
            map.put(
                    "severity",
                    state.severity() == null ? null : state.severity().name());
            map.put("status", state.status() == null ? null : state.status().name());
            map.put("policyId", state.policyId());
            map.put(
                    "firstSeen",
                    state.firstSeen() == null ? null : state.firstSeen().toString());
            map.put(
                    "lastSeen",
                    state.lastSeen() == null ? null : state.lastSeen().toString());
            map.put("count", state.count());
            long ttlSeconds = status == AlarmStatus.RESOLVED
                    ? properties.getAlarm().getResolvedRetentionSeconds()
                    : properties.getAlarm().getActiveTtlSeconds();
            redisTemplate
                    .opsForValue()
                    .set(
                            keyFor(state.fingerprint()),
                            objectMapper.writeValueAsString(map),
                            Duration.ofSeconds(ttlSeconds));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write active alarm state", ex);
        }
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static Instant instant(Object raw) {
        String value = string(raw);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static long number(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String value = string(raw);
        return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    }

    private static AlarmSeverity severity(Object raw) {
        String value = string(raw);
        return value == null || value.isBlank() ? null : AlarmSeverity.valueOf(value);
    }

    private static AlarmStatus status(Object raw) {
        String value = string(raw);
        return value == null || value.isBlank() ? null : AlarmStatus.valueOf(value);
    }
}
