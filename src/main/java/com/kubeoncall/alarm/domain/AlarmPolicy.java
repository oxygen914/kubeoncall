package com.kubeoncall.alarm.domain;

import java.util.Map;

/**
 * A configured alarm policy loaded from {@code alarm-policies.yml}.
 *
 * <p>Policies are declarative: they carry the match condition, the base severity, the PromQL/window
 * the diagnostic nodes should use, the runbook binding, static RAG metadata filters, and the
 * action profile. Code only loads, matches, and applies — no thresholds are hard-coded in Java.
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
        Map<String, String> labels,
        Map<String, String> ragFilters,
        String version
) {

    public AlarmPolicy {
        if (labels == null) {
            labels = Map.of();
        }
        if (ragFilters == null) {
            ragFilters = Map.of();
        }
        labels = Map.copyOf(labels);
        ragFilters = Map.copyOf(ragFilters);
        version = version == null || version.isBlank() ? "unversioned" : version;
    }

    public AlarmPolicy(String id,
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
                       Map<String, String> labels,
                       Map<String, String> ragFilters) {
        this(id, name, category, metricName, resourceType, severity, condition,
                promql, window, recover, runbookId, owner, actions, labels, ragFilters, "unversioned");
    }

    public AlarmPolicy(String id,
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
                       Map<String, String> labels) {
        this(id, name, category, metricName, resourceType, severity, condition,
                promql, window, recover, runbookId, owner, actions, labels, Map.of(), "unversioned");
    }
}
