package com.kubeoncall.sandbox.domain;

/**
 * The four sandbox run modes, mirroring the capability families in the refactor plan (§6.1). A run
 * mode fixes the runtime contract — image digest, entrypoint, schemas, resource limits and network
 * posture are resolved server-side from the tool catalog, so callers can never submit an arbitrary
 * image or command. Each mode also carries its default safety posture: whether production
 * credentials are ever permitted (never, for any mode) and the baseline risk used by the execution
 * policy to decide approval.
 *
 * <p>This is the single authoritative definition of a run mode. The feature-flag toggles on
 * {@code KubeOnCallProperties.Sandbox} gate <em>whether</em> a mode may be used in this deployment;
 * this enum describes <em>what</em> a mode means once permitted.
 */
public enum SandboxRunMode {
    /** Fixed, catalog-supplied diagnostic tools (log/config analysis). Lowest risk, no approval. */
    FIXED_DIAGNOSTIC(false, false, SandboxRiskLevel.LOW),
    /** Agent-generated Python or shell. No approval when the auto-run policy is satisfied. */
    GENERATED_CODE(false, false, SandboxRiskLevel.MEDIUM),
    /** YAML, Helm, patch and runbook validation. No approval, no production credentials. */
    MANIFEST_VALIDATION(false, false, SandboxRiskLevel.LOW),
    /** Remediation rehearsal in an isolated non-production cluster. Approval gated by cost policy. */
    REMEDIATION_SIMULATION(true, false, SandboxRiskLevel.HIGH);

    private final boolean defaultRequiresApproval;
    private final boolean allowsProductionCredentials;
    private final SandboxRiskLevel defaultRiskLevel;

    SandboxRunMode(
            boolean defaultRequiresApproval, boolean allowsProductionCredentials, SandboxRiskLevel defaultRiskLevel) {
        this.defaultRequiresApproval = defaultRequiresApproval;
        this.allowsProductionCredentials = allowsProductionCredentials;
        this.defaultRiskLevel = defaultRiskLevel;
    }

    /** Whether the baseline execution policy requires human approval for this mode. */
    public boolean defaultRequiresApproval() {
        return defaultRequiresApproval;
    }

    /**
     * Whether production credentials may ever be injected into a run of this mode. Always
     * {@code false}: the sandbox is a non-production boundary by construction.
     */
    public boolean allowsProductionCredentials() {
        return allowsProductionCredentials;
    }

    /** The baseline risk level assigned to a run of this mode unless overridden by the tool. */
    public SandboxRiskLevel defaultRiskLevel() {
        return defaultRiskLevel;
    }
}
