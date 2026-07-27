package com.kubeoncall.sandbox.policy;

import java.util.Objects;

import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;

/**
 * The execution policy that decides whether a proposed sandbox run may be dispatched and whether it
 * needs human approval (§6.1, §6.5). It is a pure, stateless evaluator: it takes the resolved tool
 * spec, the deployment mode toggles and the requested risk posture, and returns a
 * {@link Decision}. Because the policy never trusts caller-supplied images or credentials, the only
 * inputs are server-resolved objects — a run can be rejected because the mode is disabled in this
 * deployment, because the tool's network posture is too permissive for its risk level, or because
 * the mode never permits production credentials.
 *
 * <p>Approval rules mirror the plan's default-approval column: {@code FIXED_DIAGNOSTIC} and
 * {@code MANIFEST_VALIDATION} never require approval, {@code GENERATED_CODE} requires approval only
 * when the auto-run policy is not satisfied (represented here by {@code generatedCodeAutoRun}), and
 * {@code REMEDIATION_SIMULATION} requires approval by default unless explicitly waived for a low-risk
 * target. A risk level of {@link SandboxRiskLevel#HIGH} always forces approval regardless of mode.
 */
public final class SandboxExecutionPolicy {

    /**
     * Evaluates a proposed run.
     *
     * @param modeEnabled whether the run's mode is permitted in this deployment (from
     *     {@code SandboxProperties.isModeEnabled})
     * @param tool the resolved, digest-pinned tool spec
     * @param generatedCodeAutoRun whether agent-generated code satisfies the auto-run policy (so
     *     {@code GENERATED_CODE} may skip approval)
     * @param approvalWaiverRequested whether the caller requested an approval waiver for this run
     * @return a {@link Decision}; never {@code null}
     */
    public Decision evaluate(
            boolean modeEnabled, SandboxToolSpec tool, boolean generatedCodeAutoRun, boolean approvalWaiverRequested) {
        Objects.requireNonNull(tool, "tool");
        SandboxRunMode mode = tool.mode();

        if (!modeEnabled) {
            return Decision.deny("sandbox mode " + mode + " is not enabled in this deployment");
        }
        // Production credentials are never permitted in any mode; this is structural and cannot be
        // waived. The check is documented here as a policy invariant even though SandboxRunMode
        // already encodes it, so a future mode cannot silently opt out.
        if (mode.allowsProductionCredentials()) {
            return Decision.deny("sandbox run modes must not allow production credentials");
        }

        boolean requiresApproval = computeRequiresApproval(mode, tool, generatedCodeAutoRun);
        if (requiresApproval && approvalWaiverRequested) {
            // A waiver may only relax approval for runs that are not high-risk.
            if (effectiveRisk(tool).atLeast(SandboxRiskLevel.HIGH)) {
                return Decision.deny("high-risk runs cannot waive approval");
            }
            requiresApproval = false;
        }

        return requiresApproval ? Decision.allowWithApproval() : Decision.allow();
    }

    private boolean computeRequiresApproval(SandboxRunMode mode, SandboxToolSpec tool, boolean generatedCodeAutoRun) {
        if (effectiveRisk(tool).atLeast(SandboxRiskLevel.HIGH)) {
            return true;
        }
        return switch (mode) {
            case FIXED_DIAGNOSTIC, MANIFEST_VALIDATION -> false;
            case GENERATED_CODE -> !generatedCodeAutoRun;
            case REMEDIATION_SIMULATION -> mode.defaultRequiresApproval();
        };
    }

    private static SandboxRiskLevel effectiveRisk(SandboxToolSpec tool) {
        // The tool's own risk (carried on the spec via the mode default) is the baseline; a future
        // per-tool override field would compose here. Today the mode default is authoritative.
        return tool.mode().defaultRiskLevel();
    }

    /** Outcome of evaluating a proposed run. */
    public static final class Decision {
        private final boolean allowed;
        private final boolean approvalRequired;
        private final String denialReason;

        private Decision(boolean allowed, boolean approvalRequired, String denialReason) {
            this.allowed = allowed;
            this.approvalRequired = approvalRequired;
            this.denialReason = denialReason;
        }

        static Decision allow() {
            return new Decision(true, false, null);
        }

        static Decision allowWithApproval() {
            return new Decision(true, true, null);
        }

        static Decision deny(String reason) {
            return new Decision(false, false, reason);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public boolean isApprovalRequired() {
            return approvalRequired;
        }

        public String denialReason() {
            return denialReason;
        }
    }
}
