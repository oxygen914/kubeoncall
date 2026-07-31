package com.kubeoncall.sandbox.domain;

/**
 * Data classification label attached to every sandbox artifact (§6.3, §7.2). Classification drives
 * retention, download authorization and redaction: artifacts marked {@link #UNTRUSTED} (the default
 * for run output) are treated as prompt-injection carriers and may never be fed back to the agent
 * as a system instruction or piped into a production executor.
 */
public enum SandboxClassification {
    /** Public-safe diagnostic evidence: redacted logs, hashes, summaries. */
    PUBLIC,
    /** Internal operator data; not for external disclosure. */
    INTERNAL,
    /** Output produced by untrusted code; assumed to carry injection attempts. */
    UNTRUSTED;
}
