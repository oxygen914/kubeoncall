package com.kubeoncall.agent.planner;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Structured planner invocation result. A missing decision is never ambiguous: callers can inspect
 * the actual mode and stable downgrade reason.
 */
public record PlannerLlmResult(
        PlannerLlmDecision decision,
        PlannerMode mode,
        boolean degraded,
        PlannerDegradedReason degradedReason,
        String provider,
        String model,
        long latencyMs,
        Map<String, Long> tokenUsage,
        Instant observedAt) {

    public PlannerLlmResult {
        mode = mode == null ? PlannerMode.UNAVAILABLE : mode;
        provider = safe(provider);
        model = safe(model);
        tokenUsage = tokenUsage == null ? Map.of() : Map.copyOf(tokenUsage);
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }

    public Optional<PlannerLlmDecision> optionalDecision() {
        return Optional.ofNullable(decision);
    }

    public static PlannerLlmResult degraded(
            PlannerMode mode, PlannerDegradedReason reason, String provider, String model, long latencyMs) {
        return new PlannerLlmResult(
                null, mode, true, reason, provider, model, Math.max(0, latencyMs), Map.of(), Instant.now());
    }

    public static PlannerLlmResult success(
            PlannerLlmDecision decision,
            PlannerMode mode,
            String provider,
            String model,
            long latencyMs,
            Map<String, Long> tokenUsage) {
        return new PlannerLlmResult(
                decision, mode, false, null, provider, model, Math.max(0, latencyMs), tokenUsage, Instant.now());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
