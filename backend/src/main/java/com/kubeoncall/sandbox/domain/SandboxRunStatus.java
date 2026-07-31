package com.kubeoncall.sandbox.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle status of a sandbox run (§6.2). Terminal states — {@link #SUCCEEDED}, {@link #FAILED},
 * {@link #TIMED_OUT}, {@link #CANCELLED} — may never transition backwards or be overwritten by a
 * stale controller callback. {@link #CANCELLED} signals that the business cancellation has been
 * accepted; it is still distinct from {@link SandboxCleanupStatus} because resource cleanup must
 * complete independently.
 *
 * <p>Allowed transitions are declared once here via {@link #allowedNext()}; the
 * {@code SandboxRunStateMachine} is the single authority that consults this table, so every status
 * change routes through one place and cannot bypass the terminal invariant.
 */
public enum SandboxRunStatus {
    PENDING,
    DISPATCHING,
    RUNNING,
    COLLECTING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    /**
     * The states this status may legally transition to. Terminal states return an empty set.
     */
    public Set<SandboxRunStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(DISPATCHING, CANCELLED, FAILED);
            case DISPATCHING -> EnumSet.of(RUNNING, FAILED, TIMED_OUT, CANCELLED);
            case RUNNING -> EnumSet.of(COLLECTING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED);
            case COLLECTING -> EnumSet.of(SUCCEEDED, FAILED, TIMED_OUT, CANCELLED);
            case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED -> EnumSet.noneOf(SandboxRunStatus.class);
        };
    }

    /** True when no further business transition is permitted from this status. */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
