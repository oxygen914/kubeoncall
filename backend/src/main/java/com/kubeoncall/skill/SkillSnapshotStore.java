package com.kubeoncall.skill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Persists the last valid Skill index and bodies for recovery after a failed resource scan. */
@Service
public class SkillSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SkillSnapshotStore.class);
    private static final String SNAPSHOT_KEY = "skill:snapshot:v1";
    private static final TypeReference<List<Skill>> SKILL_LIST = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private volatile List<Skill> localSnapshot = List.of();

    public SkillSnapshotStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }
        List<Skill> snapshot = List.copyOf(skills);
        localSnapshot = snapshot;
        try {
            redisTemplate.opsForValue().set(SNAPSHOT_KEY, objectMapper.writeValueAsString(snapshot));
        } catch (Exception ex) {
            log.warn(
                    "Unable to persist Skill snapshot; local cache remains available: errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    public List<Skill> load() {
        try {
            String raw = redisTemplate.opsForValue().get(SNAPSHOT_KEY);
            if (raw != null && !raw.isBlank()) {
                List<Skill> restored = objectMapper.readValue(raw, SKILL_LIST);
                if (restored != null && !restored.isEmpty()) {
                    localSnapshot = List.copyOf(restored);
                }
            }
        } catch (Exception ex) {
            log.warn(
                    "Unable to load Skill snapshot from Redis; using local cache: errorType={}",
                    ex.getClass().getSimpleName());
        }
        return localSnapshot;
    }
}
