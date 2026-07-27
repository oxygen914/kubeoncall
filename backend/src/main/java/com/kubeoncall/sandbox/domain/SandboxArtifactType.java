package com.kubeoncall.sandbox.domain;

/**
 * The kind of artifact stored for a sandbox run (§6.3). The object-key prefix in the artifact
 * store is derived from this type, so the set of prefixes is fixed by the enum and cannot be
 * supplied by a caller — this prevents object-key traversal at the type level.
 */
public enum SandboxArtifactType {
    /** Evidence, scripts and manifests supplied to the run as input. */
    INPUT,
    /** Structured or raw output produced by the run. */
    OUTPUT,
    /** Truncated, redacted pod/job logs captured from the run. */
    LOG,
    /** A structured diagnosis or validation report. */
    REPORT;
}
