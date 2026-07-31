package com.kubeoncall.agent.planner;

import java.util.Locale;

/** Declares the actual source used to produce a planner decision. */
public enum PlannerMode {
    REAL_MODEL(true, false),
    RULE_ASSISTED(true, false),
    RULE_FALLBACK(false, true),
    SIMULATION(false, true),
    UNAVAILABLE(false, true);

    private final boolean mutationCandidateAllowed;
    private final boolean degraded;

    PlannerMode(boolean mutationCandidateAllowed, boolean degraded) {
        this.mutationCandidateAllowed = mutationCandidateAllowed;
        this.degraded = degraded;
    }

    public boolean mutationCandidateAllowed() {
        return mutationCandidateAllowed;
    }

    public boolean degraded() {
        return degraded;
    }

    public static PlannerMode configured(String value) {
        if (value == null || value.isBlank()) {
            return RULE_FALLBACK;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNAVAILABLE;
        }
    }

    public static PlannerMode runtime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return UNAVAILABLE;
        }
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNAVAILABLE;
        }
    }
}
