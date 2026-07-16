package com.kubeoncall.alarm.correlation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;

/** Finds recent, scope-matched changes and returns a bounded explainable correlation set. */
@Service
public class ChangeCorrelationService {

    private final ChangeEventRepository changeEventRepository;

    public ChangeCorrelationService(ChangeEventRepository changeEventRepository) {
        this.changeEventRepository = changeEventRepository;
    }

    public void record(ChangeEvent event) {
        if (event == null || event.changeId() == null || event.changeId().isBlank()) {
            throw new IllegalArgumentException("changeId is required");
        }
        changeEventRepository.save(event);
    }

    public List<ChangeCorrelation> findRelatedChanges(NormalizedAlarmEvent alarm) {
        Instant occurredAt = alarm.occurredAt() == null ? Instant.now() : alarm.occurredAt();
        return changeEventRepository
                .findBetween(
                        occurredAt.minus(Duration.ofHours(1)),
                        occurredAt.plus(Duration.ofMinutes(30)),
                        alarm.cluster(),
                        alarm.namespace())
                .stream()
                .map(change -> correlate(alarm, change, occurredAt))
                .filter(correlation -> correlation.correlationScore() >= 0.3)
                .sorted(Comparator.comparingDouble(ChangeCorrelation::correlationScore)
                        .reversed())
                .limit(5)
                .toList();
    }

    private ChangeCorrelation correlate(NormalizedAlarmEvent alarm, ChangeEvent change, Instant occurredAt) {
        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        double score = timeScore(change.changedAt(), occurredAt);
        if (score >= 0.25) {
            reasons.add("change occurred close to the alarm");
        }
        if (same(alarm.resourceName(), change.resourceName())) {
            score += 0.4;
            reasons.add("resource matches");
        } else if (same(alarm.service(), change.resourceName())) {
            score += 0.3;
            reasons.add("service matches");
        } else if (same(alarm.namespace(), change.namespace())) {
            score += 0.15;
            reasons.add("namespace matches");
        }
        score += riskWeight(change.changeType());
        if ("deployment_image_change".equalsIgnoreCase(change.changeType())) {
            suggestions.add("Review the deployment image change and submit an approved rollback if it is causal");
        }
        if ("configmap_change".equalsIgnoreCase(change.changeType())
                || "secret_change".equalsIgnoreCase(change.changeType())) {
            suggestions.add("Compare the changed configuration with the last known-good revision");
        }
        return new ChangeCorrelation(change, Math.min(1, score), String.join("; ", reasons), suggestions);
    }

    private double timeScore(Instant changedAt, Instant occurredAt) {
        long seconds = Math.abs(Duration.between(changedAt, occurredAt).toSeconds());
        return Math.max(0, 0.3 * (1 - seconds / 3600.0));
    }

    private double riskWeight(String changeType) {
        if (changeType == null) {
            return 0;
        }
        return switch (changeType.toLowerCase(java.util.Locale.ROOT)) {
            case "deployment_image_change", "node_drain" -> 0.18;
            case "deployment_env_change", "secret_change" -> 0.14;
            case "configmap_change" -> 0.12;
            case "hpa_spec_change" -> 0.08;
            default -> 0.04;
        };
    }

    private boolean same(String left, String right) {
        return left != null && !left.isBlank() && left.equalsIgnoreCase(right);
    }
}
