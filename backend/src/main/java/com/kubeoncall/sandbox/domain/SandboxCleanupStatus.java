package com.kubeoncall.sandbox.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle status of the resource cleanup for a sandbox run (§6.2). Cleanup is tracked separately
 * from {@link SandboxRunStatus} so that "run succeeded but the Job was not reaped" is reported as a
 * leak rather than masked as full success. {@link #NOT_REQUIRED} means no managed resources were
 * ever created (e.g. validation rejected the run before dispatch); {@link #SUCCEEDED} is the only
 * terminal success and {@link #FAILED} is a terminal leak that must raise a {@code CLEANUP_FAILED}
 * alert.
 *
 * <p>As with run status, the allowed-transition table is the single source consulted by the state
 * machine, enforcing that a terminal cleanup state can never be regressed.
 */
public enum SandboxCleanupStatus {
    NOT_REQUIRED,
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    /** The states this status may legally transition to. Terminal states return an empty set. */
    public Set<SandboxCleanupStatus> allowedNext() {
        return switch (this) {
            case NOT_REQUIRED -> EnumSet.of(PENDING);
            case PENDING -> EnumSet.of(RUNNING, SUCCEEDED, FAILED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED);
            case SUCCEEDED, FAILED -> EnumSet.noneOf(SandboxCleanupStatus.class);
        };
    }

    /** True when no further cleanup transition is permitted from this status. */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
