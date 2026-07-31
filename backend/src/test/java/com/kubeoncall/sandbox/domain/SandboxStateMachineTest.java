package com.kubeoncall.sandbox.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

/**
 * Full-combination coverage of {@link SandboxStateMachine}: every legal transition is asserted
 * reachable, every illegal one rejected, and terminal states are immutable. Cancellation precedence
 * over late controller callbacks is verified separately.
 */
class SandboxStateMachineTest {

    private final SandboxStateMachine machine = new SandboxStateMachine();

    @Test
    void runStatusAllowedTransitionsShouldMatchPlanLifecycle() {
        assertEquals(
                EnumSet.of(SandboxRunStatus.DISPATCHING, SandboxRunStatus.CANCELLED, SandboxRunStatus.FAILED),
                SandboxRunStatus.PENDING.allowedNext());
        assertEquals(
                EnumSet.of(
                        SandboxRunStatus.RUNNING,
                        SandboxRunStatus.FAILED,
                        SandboxRunStatus.TIMED_OUT,
                        SandboxRunStatus.CANCELLED),
                SandboxRunStatus.DISPATCHING.allowedNext());
        assertEquals(
                EnumSet.of(
                        SandboxRunStatus.COLLECTING,
                        SandboxRunStatus.SUCCEEDED,
                        SandboxRunStatus.FAILED,
                        SandboxRunStatus.TIMED_OUT,
                        SandboxRunStatus.CANCELLED),
                SandboxRunStatus.RUNNING.allowedNext());
        assertEquals(
                EnumSet.of(
                        SandboxRunStatus.SUCCEEDED,
                        SandboxRunStatus.FAILED,
                        SandboxRunStatus.TIMED_OUT,
                        SandboxRunStatus.CANCELLED),
                SandboxRunStatus.COLLECTING.allowedNext());
    }

    @Test
    void terminalRunStatusesShouldHaveNoOutgoingTransitions() {
        for (SandboxRunStatus terminal : EnumSet.of(
                SandboxRunStatus.SUCCEEDED,
                SandboxRunStatus.FAILED,
                SandboxRunStatus.TIMED_OUT,
                SandboxRunStatus.CANCELLED)) {
            assertTrue(terminal.isTerminal(), terminal + " should be terminal");
            assertTrue(terminal.allowedNext().isEmpty(), terminal + " should allow no next state");
        }
    }

    @Test
    void shouldResolveLegalRunTransitions() {
        assertEquals(
                SandboxRunStatus.RUNNING,
                machine.resolveRunStatus(SandboxRunStatus.DISPATCHING, SandboxRunStatus.RUNNING));
        assertEquals(
                SandboxRunStatus.SUCCEEDED,
                machine.resolveRunStatus(SandboxRunStatus.COLLECTING, SandboxRunStatus.SUCCEEDED));
        assertEquals(
                SandboxRunStatus.CANCELLED,
                machine.resolveRunStatus(SandboxRunStatus.RUNNING, SandboxRunStatus.CANCELLED));
    }

    @Test
    void shouldRejectIllegalRunTransitions() {
        // PENDING cannot jump straight to RUNNING (dispatch must happen first).
        assertThrows(
                SandboxRunStateException.class,
                () -> machine.resolveRunStatus(SandboxRunStatus.PENDING, SandboxRunStatus.RUNNING));
        // SUCCEEDED cannot move to FAILED.
        assertThrows(
                SandboxRunStateException.class,
                () -> machine.resolveRunStatus(SandboxRunStatus.SUCCEEDED, SandboxRunStatus.FAILED));
        // CANCELLED cannot move back to RUNNING.
        assertThrows(
                SandboxRunStateException.class,
                () -> machine.resolveRunStatus(SandboxRunStatus.CANCELLED, SandboxRunStatus.RUNNING));
    }

    @Test
    void shouldRejectAnyTransitionOutOfTerminalRunStatus() {
        for (SandboxRunStatus terminal : EnumSet.of(
                SandboxRunStatus.SUCCEEDED,
                SandboxRunStatus.FAILED,
                SandboxRunStatus.TIMED_OUT,
                SandboxRunStatus.CANCELLED)) {
            for (SandboxRunStatus target : SandboxRunStatus.values()) {
                assertThrows(
                        SandboxRunStateException.class,
                        () -> machine.resolveRunStatus(terminal, target),
                        "terminal " + terminal + " should reject transition to " + target);
            }
        }
    }

    @Test
    void cleanupStatusAllowedTransitionsShouldMatchPlanLifecycle() {
        assertEquals(EnumSet.of(SandboxCleanupStatus.PENDING), SandboxCleanupStatus.NOT_REQUIRED.allowedNext());
        assertEquals(
                EnumSet.of(SandboxCleanupStatus.RUNNING, SandboxCleanupStatus.SUCCEEDED, SandboxCleanupStatus.FAILED),
                SandboxCleanupStatus.PENDING.allowedNext());
        assertEquals(
                EnumSet.of(SandboxCleanupStatus.SUCCEEDED, SandboxCleanupStatus.FAILED),
                SandboxCleanupStatus.RUNNING.allowedNext());
    }

    @Test
    void terminalCleanupStatusesShouldBeImmutable() {
        for (SandboxCleanupStatus terminal : EnumSet.of(SandboxCleanupStatus.SUCCEEDED, SandboxCleanupStatus.FAILED)) {
            assertTrue(terminal.isTerminal());
            assertThrows(
                    SandboxRunStateException.class,
                    () -> machine.resolveCleanupStatus(terminal, SandboxCleanupStatus.RUNNING));
        }
    }

    @Test
    void callbackIsApplicableShouldLetNonTerminalCurrentAcceptLegalReport() {
        assertTrue(machine.callbackIsApplicable(SandboxRunStatus.RUNNING, SandboxRunStatus.SUCCEEDED));
        assertTrue(machine.callbackIsApplicable(SandboxRunStatus.COLLECTING, SandboxRunStatus.FAILED));
    }

    @Test
    void callbackIsApplicableShouldRejectLateCallbackAfterCancellation() {
        // A late success arriving after the run was cancelled must not overwrite the terminal state.
        assertFalse(machine.callbackIsApplicable(SandboxRunStatus.CANCELLED, SandboxRunStatus.SUCCEEDED));
        // A duplicate terminal report is also ignored.
        assertFalse(machine.callbackIsApplicable(SandboxRunStatus.SUCCEEDED, SandboxRunStatus.SUCCEEDED));
        // And a structurally illegal report (PENDING -> SUCCEEDED) is rejected.
        assertFalse(machine.callbackIsApplicable(SandboxRunStatus.PENDING, SandboxRunStatus.SUCCEEDED));
    }
}
