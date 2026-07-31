package com.kubeoncall.alarm.policy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Persists validated policy snapshots so rollback history survives application restarts. */
@Service
public class AlarmPolicySnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(AlarmPolicySnapshotStore.class);
    private static final String SNAPSHOT_KEY = "alarm-policy:snapshots:v1";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private volatile Map<String, YamlAlarmPolicyRepository.PolicySnapshot> local = Map.of();

    public AlarmPolicySnapshotStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(Map<String, YamlAlarmPolicyRepository.PolicySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        local = Map.copyOf(snapshots);
        try {
            Map<String, String> serialized = new LinkedHashMap<>();
            snapshots.forEach((version, snapshot) -> {
                try {
                    serialized.put(version, objectMapper.writeValueAsString(snapshot));
                } catch (Exception ex) {
                    throw new IllegalStateException("Unable to serialize policy snapshot", ex);
                }
            });
            redisTemplate.delete(SNAPSHOT_KEY);
            redisTemplate.opsForHash().putAll(SNAPSHOT_KEY, serialized);
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to persist policy snapshots; local history remains available: errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    public Map<String, YamlAlarmPolicyRepository.PolicySnapshot> load() {
        try {
            Map<Object, Object> stored = redisTemplate.opsForHash().entries(SNAPSHOT_KEY);
            if (stored != null && !stored.isEmpty()) {
                Map<String, YamlAlarmPolicyRepository.PolicySnapshot> restored = new LinkedHashMap<>();
                for (Map.Entry<Object, Object> entry : stored.entrySet()) {
                    restored.put(
                            String.valueOf(entry.getKey()),
                            objectMapper.readValue(
                                    String.valueOf(entry.getValue()), YamlAlarmPolicyRepository.PolicySnapshot.class));
                }
                local = Map.copyOf(restored);
            }
        } catch (Exception ex) {
            log.warn(
                    "Unable to restore policy snapshots; using local history: errorType={}",
                    ex.getClass().getSimpleName());
        }
        return local;
    }
}
