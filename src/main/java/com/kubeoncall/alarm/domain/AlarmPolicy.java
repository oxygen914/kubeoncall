package com.kubeoncall.alarm.domain;

import java.util.Map;

/**
 * A configured alarm policy loaded from {@code alarm-policies.yml}.
 *
 * <p>Policies are declarative: they carry the match condition, the base severity, the PromQL/window
 * the diagnostic nodes should use, the runbook binding, and the action profile. Code only loads,
 * matches, and applies — no thresholds are hard-coded in Java.
 */
public record AlarmPolicy(
        String id,
        String name,
        String category,
        String metricName,
        AlarmResourceType resourceType,
        AlarmSeverity severity,
        AlarmCondition condition,
        String promql,
        String window,
        String recover,
        String runbookId,
        String owner,
        AlarmAction actions,
        Map<String, String> labels
) {

    public AlarmPolicy {
        if (labels == null) {
            labels = Map.of();
        }
    }
}
