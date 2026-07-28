package com.kubeoncall.skill;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Service
public class SkillStateStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStateStore.class);
    private static final String DISABLED_KEY = "skill:disabled";
    private static final String DOMAIN = "skill-state";
    private static final Map<String, String> LEGACY_SKILL_IDS = Map.of("payment-oom-triage", "pod-oom-triage");

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;
    private final Set<String> localDisabled = ConcurrentHashMap.newKeySet();

    public SkillStateStore(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    public Set<String> disabledIds() {
        try {
            Set<String> members = redisTemplate.opsForSet().members(DISABLED_KEY);
            if (members != null) {
                localDisabled.clear();
                localDisabled.addAll(members);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to refresh disabled skill state from Redis; using local cache: errorType={}",
                    ex.getClass().getSimpleName());
        }
        Set<String> disabled = new LinkedHashSet<>(localDisabled);
        LEGACY_SKILL_IDS.forEach((legacyId, currentId) -> {
            if (localDisabled.contains(legacyId)) {
                disabled.add(currentId);
            }
        });
        return Set.copyOf(disabled);
    }

    public void disable(String skillId) {
        if (legacyWriteDisabled()) {
            log.warn("Skipping Redis skill-state write; legacy write disabled for skillId={}", skillId);
            metricsService.recordLegacyWriteSkipped(DOMAIN);
            return;
        }
        localDisabled.add(skillId);
        redisTemplate.opsForSet().add(DISABLED_KEY, skillId);
    }

    public void enable(String skillId) {
        if (legacyWriteDisabled()) {
            log.warn("Skipping Redis skill-state write; legacy write disabled for skillId={}", skillId);
            metricsService.recordLegacyWriteSkipped(DOMAIN);
            return;
        }
        Set<String> equivalentIds = equivalentIds(skillId);
        localDisabled.removeAll(equivalentIds);
        redisTemplate.opsForSet().remove(DISABLED_KEY, equivalentIds.toArray());
    }

    public boolean isEnabled(String skillId) {
        return !disabledIds().contains(skillId);
    }

    private boolean legacyWriteDisabled() {
        return properties.getDataMigration().getSkillState().legacyWriteDisabled();
    }

    private Set<String> equivalentIds(String skillId) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(skillId);
        LEGACY_SKILL_IDS.forEach((legacyId, currentId) -> {
            if (currentId.equals(skillId)) {
                ids.add(legacyId);
            }
        });
        return ids;
    }
}
