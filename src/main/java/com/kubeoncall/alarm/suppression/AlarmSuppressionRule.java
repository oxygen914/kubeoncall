package com.kubeoncall.alarm.suppression;

import com.kubeoncall.alarm.domain.AlarmResourceType;

import java.util.List;

public record AlarmSuppressionRule(
        String id,
        Match source,
        Match target,
        List<String> correlateBy,
        long ttlSeconds,
        String reason
) {
    public AlarmSuppressionRule {
        correlateBy = correlateBy == null ? List.of() : List.copyOf(correlateBy);
    }

    public record Match(List<AlarmResourceType> resourceTypes, List<String> alertNamePatterns) {
        public Match {
            resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes);
            alertNamePatterns = alertNamePatterns == null ? List.of() : List.copyOf(alertNamePatterns);
        }
    }
}
