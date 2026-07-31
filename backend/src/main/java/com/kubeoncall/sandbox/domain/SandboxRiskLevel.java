package com.kubeoncall.sandbox.domain;

/**
 * Risk classification for a sandbox run, used by the execution policy to decide whether human
 * approval is required before dispatch. Levels are ordered: a higher ordinal is a higher risk, so
 * {@link #atLeast(SandboxRiskLevel)} and {@link #isHigherThan(SandboxRiskLevel)} can gate policy
 * without a lookup table.
 */
public enum SandboxRiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    /** True when this level is at or above the given threshold. */
    public boolean atLeast(SandboxRiskLevel threshold) {
        return ordinal() >= threshold.ordinal();
    }

    /** True when this level is strictly above the given threshold. */
    public boolean isHigherThan(SandboxRiskLevel threshold) {
        return ordinal() > threshold.ordinal();
    }
}
