package com.kubeoncall.skill;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SkillStateStore {

    private static final String DISABLED_KEY = "skill:disabled";

    private final StringRedisTemplate redisTemplate;
    private final Set<String> localDisabled = ConcurrentHashMap.newKeySet();

    public SkillStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Set<String> disabledIds() {
        try {
            Set<String> members = redisTemplate.opsForSet().members(DISABLED_KEY);
            if (members != null) {
                localDisabled.clear();
                localDisabled.addAll(members);
            }
        } catch (RuntimeException ignored) {
        }
        return Set.copyOf(localDisabled);
    }

    public void disable(String skillId) {
        localDisabled.add(skillId);
        redisTemplate.opsForSet().add(DISABLED_KEY, skillId);
    }

    public void enable(String skillId) {
        localDisabled.remove(skillId);
        redisTemplate.opsForSet().remove(DISABLED_KEY, skillId);
    }

    public boolean isEnabled(String skillId) {
        return !disabledIds().contains(skillId);
    }
}
