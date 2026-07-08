package com.kubeoncall.alarm.domain;

/**
 * Platform-wide alarm severity levels.
 *
 * <p>Severity is the most actionable signal: it drives notification channels, dedup TTLs,
 * escalation, and whether an automated action requires approval. The ordering is meaningful —
 * {@link #rank()} lets the policy engine compare severities and apply impact-factor adjustments.
 */
public enum AlarmSeverity {

    /** Critical: core service down, data risk, or widespread failure. Page on-call immediately. */
    P0(0),
    /** Major: clear stability/capacity impact that may escalate to P0. */
    P1(1),
    /** Moderate: limited impact, handle during working hours. */
    P2(2),
    /** Low: trend or capacity-planning reminder. */
    P3(3),
    /** Informational: state change or recovery event, no strong response required. */
    INFO(4);

    private final int rank;

    AlarmSeverity(int rank) {
        this.rank = rank;
    }

    /** Lower rank means higher severity (P0=0 is the most severe). */
    public int rank() {
        return rank;
    }

    /**
     * Adjust severity by the given delta. Positive delta downgrades (less severe), negative delta
     * upgrades (more severe). Clamped to the {@code P0..INFO} range so adjustments can never escape
     * the model.
     */
    public AlarmSeverity adjust(int delta) {
        if (delta == 0) {
            return this;
        }
        int target = Math.min(INFO.rank, Math.max(P0.rank, rank + delta));
        for (AlarmSeverity s : values()) {
            if (s.rank == target) {
                return s;
            }
        }
        return this;
    }

    /**
     * Parse a raw upstream severity string (e.g. {@code "critical"}, {@code "warning"}, {@code "P1"})
     * into a platform severity. Returns {@code null} when the input cannot be mapped, so the caller
     * can fall back to a policy-derived default.
     */
    public static AlarmSeverity fromRaw(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "P0", "CRITICAL", "CRIT", "PAGE", "SEV1", "SEV-1" -> P0;
            case "P1", "ERROR", "HIGH", "SEV2", "SEV-2" -> P1;
            case "P2", "WARNING", "WARN", "MEDIUM", "SEV3", "SEV-3" -> P2;
            case "P3", "LOW", "SEV4", "SEV-4" -> P3;
            case "INFO", "INFORMATIONAL", "NOTICE", "RESOLVED", "RECOVERY" -> INFO;
            default -> {
                for (AlarmSeverity s : values()) {
                    if (s.name().equals(normalized)) {
                        yield s;
                    }
                }
                yield null;
            }
        };
    }
}
