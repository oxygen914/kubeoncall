package com.kubeoncall.sandbox.domain;

/**
 * Pure authority over sandbox run and cleanup status transitions (§6.2). Every status change in the
 * system routes through here, so the terminal-state invariant and the allowed-transition tables
 * declared on the enums are the only enforcement point. The machine holds no state of its own — it
 * is a stateless policy — which keeps concurrent reconcilers safe: the persisted {@code version}
 * and {@code owner_token + fencing_token} guard against lost updates, and this class only decides
 * whether a proposed transition is structurally legal.
 *
 * <p>Cancellation has special handling: {@link SandboxRunStatus#CANCELLED} may be requested from
 * any non-terminal run status, but once accepted it is terminal and a late success/failure from
 * the controller must be rejected rather than overwrite it. Conversely, a terminal success cannot
 * be cancelled.
 */
public final class SandboxStateMachine {

    /**
     * Resolves the next run status for a proposed transition, or throws if it is illegal.
     *
     * @param current the persisted current status
     * @param proposed the status the caller wants to move to
     * @return {@code proposed} when legal
     * @throws SandboxRunStateException when {@code current} is terminal, or {@code proposed} is not
     *     in {@code current.allowedNext()}
     */
    public SandboxRunStatus resolveRunStatus(SandboxRunStatus current, SandboxRunStatus proposed) {
        if (current.isTerminal()) {
            throw new SandboxRunStateException(
                    "cannot transition from terminal run status " + current + " to " + proposed);
        }
        if (!current.allowedNext().contains(proposed)) {
            throw new SandboxRunStateException("illegal run status transition " + current + " -> " + proposed);
        }
        return proposed;
    }

    /**
     * Resolves the next cleanup status for a proposed transition, or throws if illegal. Mirrors
     * {@link #resolveRunStatus} for the cleanup lifecycle.
     */
    public SandboxCleanupStatus resolveCleanupStatus(SandboxCleanupStatus current, SandboxCleanupStatus proposed) {
        if (current.isTerminal()) {
            throw new SandboxRunStateException(
                    "cannot transition from terminal cleanup status " + current + " to " + proposed);
        }
        if (!current.allowedNext().contains(proposed)) {
            throw new SandboxRunStateException("illegal cleanup status transition " + current + " -> " + proposed);
        }
        return proposed;
    }

    /**
     * True when a controller callback reporting {@code reported} may be applied on top of the
     * persisted {@code current} status. A terminal current status always wins: a late success after
     * a cancellation, or a duplicate terminal report, must not overwrite what was already decided.
     */
    public boolean callbackIsApplicable(SandboxRunStatus current, SandboxRunStatus reported) {
        if (current.isTerminal()) {
            return false;
        }
        return current.allowedNext().contains(reported);
    }
}
