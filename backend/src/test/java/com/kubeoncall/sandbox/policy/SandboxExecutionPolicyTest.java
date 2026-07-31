package com.kubeoncall.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kubeoncall.sandbox.domain.SandboxRunMode;

/** Covers the mode-gate, approval and risk rules of {@link SandboxExecutionPolicy}. */
class SandboxExecutionPolicyTest {

    private static final String DIGEST = "repo@sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    private final SandboxExecutionPolicy policy = new SandboxExecutionPolicy();

    private static SandboxResourceLimits limits() {
        return new SandboxResourceLimits("1", "1Gi", "2Gi", 1024L, 1024L, 1024L, 1024L, 300, 900);
    }

    private static SandboxToolSpec tool(SandboxRunMode mode) {
        return new SandboxToolSpec(
                "tool",
                "1.0.0",
                mode,
                DIGEST,
                "/e.sh",
                limits(),
                SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                "in",
                "out");
    }

    @Test
    void shouldDenyWhenModeIsDisabled() {
        SandboxExecutionPolicy.Decision d = policy.evaluate(false, tool(SandboxRunMode.FIXED_DIAGNOSTIC), false, false);
        assertFalse(d.isAllowed());
        assertTrue(d.denialReason().contains("not enabled"));
    }

    @Test
    void fixedDiagnosticAndManifestValidationShouldNotRequireApproval() {
        SandboxExecutionPolicy.Decision diag =
                policy.evaluate(true, tool(SandboxRunMode.FIXED_DIAGNOSTIC), false, false);
        assertTrue(diag.isAllowed());
        assertFalse(diag.isApprovalRequired());

        SandboxExecutionPolicy.Decision manifest =
                policy.evaluate(true, tool(SandboxRunMode.MANIFEST_VALIDATION), false, false);
        assertTrue(manifest.isAllowed());
        assertFalse(manifest.isApprovalRequired());
    }

    @Test
    void generatedCodeShouldRequireApprovalUnlessAutoRunSatisfied() {
        SandboxExecutionPolicy.Decision withoutAutoRun =
                policy.evaluate(true, tool(SandboxRunMode.GENERATED_CODE), false, false);
        assertTrue(withoutAutoRun.isAllowed());
        assertTrue(withoutAutoRun.isApprovalRequired());

        SandboxExecutionPolicy.Decision withAutoRun =
                policy.evaluate(true, tool(SandboxRunMode.GENERATED_CODE), true, false);
        assertTrue(withAutoRun.isAllowed());
        assertFalse(withAutoRun.isApprovalRequired());
    }

    @Test
    void remediationSimulationShouldRequireApprovalByDefault() {
        SandboxExecutionPolicy.Decision d =
                policy.evaluate(true, tool(SandboxRunMode.REMEDIATION_SIMULATION), false, false);
        assertTrue(d.isAllowed());
        assertTrue(d.isApprovalRequired());
    }

    @Test
    void approvalWaiverShouldRelaxLowAndMediumRiskButNotHigh() {
        // GENERATED_CODE is MEDIUM risk: waiver relaxes approval.
        SandboxExecutionPolicy.Decision generatedWaiver =
                policy.evaluate(true, tool(SandboxRunMode.GENERATED_CODE), false, true);
        assertTrue(generatedWaiver.isAllowed());
        assertFalse(generatedWaiver.isApprovalRequired());

        // REMEDIATION_SIMULATION is HIGH risk: waiver is rejected outright.
        SandboxExecutionPolicy.Decision simWaiver =
                policy.evaluate(true, tool(SandboxRunMode.REMEDIATION_SIMULATION), false, true);
        assertFalse(simWaiver.isAllowed());
        assertTrue(simWaiver.denialReason().contains("high-risk"));
    }

    @Test
    void highRiskShouldAlwaysRequireApprovalEvenWithAutoRun() {
        // Auto-run is irrelevant for HIGH-risk remediation simulation.
        SandboxExecutionPolicy.Decision d =
                policy.evaluate(true, tool(SandboxRunMode.REMEDIATION_SIMULATION), true, false);
        assertTrue(d.isAllowed());
        assertTrue(d.isApprovalRequired());
    }
}
