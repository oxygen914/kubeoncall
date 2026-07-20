package com.kubeoncall.alarm.domain;

import java.util.Map;

/**
 * Match/trigger condition for a policy.
 *
 * <p>Matching fields ({@code alertName}, {@code metricName}, {@code resourceType},
 * {@code matchLabels})
 * are optional — a null/blank field matches anything. The {@code threshold} and {@code operator}
 * define the numeric trigger on {@code currentValue}; when {@code threshold} is null the policy is
 * treated as a state/root-cause policy that fires on match alone (e.g. {@code KubeNodeNotReady}).
 */
public record AlarmCondition(
        String alertName,
        String metricName,
        AlarmResourceType resourceType,
        String operator,
        Double threshold,
        String duration,
        Map<String, String> matchLabels) {

    public AlarmCondition {
        if (matchLabels == null) {
            matchLabels = Map.of();
        }
    }

    /** Whether the numeric trigger is satisfied for the given current value. */
    public boolean thresholdSatisfied(Double currentValue) {
        if (threshold == null) {
            return true;
        }
        if (currentValue == null) {
            return false;
        }
        return switch (operator == null ? ">" : operator.trim()) {
            case ">", "GT" -> currentValue > threshold;
            case ">=", "GE", "GTE" -> currentValue >= threshold;
            case "<", "LT" -> currentValue < threshold;
            case "<=", "LE", "LTE" -> currentValue <= threshold;
            case "=", "==", "EQ" -> Double.compare(currentValue, threshold) == 0;
            case "!=", "NE", "NEQ" -> Double.compare(currentValue, threshold) != 0;
            default -> currentValue > threshold;
        };
    }
}
