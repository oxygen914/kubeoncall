package com.kubeoncall.skill.mysql;

import java.time.Instant;
import java.util.Map;

/** API-safe persisted Skill discovery/load state. */
public record SkillStateRecord(
        String publicId,
        String skillId,
        String skillVersion,
        String checksum,
        String sourceLocation,
        boolean enabled,
        String loadStatus,
        String errorSummary,
        Map<String, Object> metadata,
        Instant lastLoadedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
