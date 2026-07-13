package com.kubeoncall.alarm.domain;

import java.util.List;
import java.util.Map;

/**
 * Outcome of running the policy engine over a {@link NormalizedAlarmEvent}.
 *
 * <p>{@code matched} is false when no policy fires (e.g. CPU 69% under a 70 threshold), in which
 * case the engine still returns a result with a default severity so the caller can decide whether
 * to drop the event or process it as informational.
 */
public record AlarmEvaluationResult(
        boolean matched,
        AlarmPolicy matchedPolicy,
        String policyId,
        AlarmSeverity finalSeverity,
        Double threshold,
        String runbookId,
        String promql,
        String window,
        String workflowTemplate,
        String reason,
        List<String> notes) {

    public AlarmEvaluationResult {
        if (notes == null) {
            notes = List.of();
        }
    }

    /** An unmatched result carrying only a reason and the computed/default severity. */
    public static AlarmEvaluationResult unmatched(AlarmSeverity severity, String reason) {
        return new AlarmEvaluationResult(false, null, null, severity, null, null, null, null, null, reason, List.of());
    }

    public Map<String, String> ragFilters() {
        return matchedPolicy == null ? Map.of() : matchedPolicy.ragFilters();
    }
}
