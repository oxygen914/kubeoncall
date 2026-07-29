package com.kubeoncall.agent.planner;

/** Stable, non-sensitive reason for a planner downgrade or unavailable result. */
public enum PlannerDegradedReason {
    DISABLED,
    RULE_MODE_CONFIGURED,
    SIMULATION_CONFIGURED,
    INVALID_MODE,
    CLIENT_MISSING,
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_ERROR,
    EMPTY_RESPONSE,
    RESPONSE_TOO_LARGE,
    SCHEMA_INVALID
}
