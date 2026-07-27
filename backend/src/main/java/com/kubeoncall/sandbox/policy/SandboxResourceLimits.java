package com.kubeoncall.sandbox.policy;

/**
 * Immutable resource limits enforced for a single sandbox run (§7.1). Values are resolved
 * server-side from the tool catalog and clamped by the deployment hard ceilings; a caller can never
 * raise them. CPU, memory and ephemeral storage are stored as opaque Kubernetes quantity strings
 * (already validated and normalized by {@code SandboxProperties}), while the scalar byte/second
 * counts are the authoritative enforcement values for input, output, logs and scripts.
 *
 * <p>This is a value type: the compact constructor rejects any non-positive enforcement value so a
 * malformed tool spec cannot create a run with, say, an unbounded output budget.
 */
public record SandboxResourceLimits(
        String cpu,
        String memory,
        String ephemeralStorage,
        long inputMaxBytes,
        long outputMaxBytes,
        long logMaxBytes,
        long scriptMaxBytes,
        int timeoutSeconds,
        int maxTimeoutSeconds) {

    public SandboxResourceLimits {
        requireQuantity("cpu", cpu);
        requireQuantity("memory", memory);
        requireQuantity("ephemeralStorage", ephemeralStorage);
        if (inputMaxBytes <= 0) {
            throw new IllegalArgumentException("inputMaxBytes must be positive");
        }
        if (outputMaxBytes <= 0) {
            throw new IllegalArgumentException("outputMaxBytes must be positive");
        }
        if (logMaxBytes <= 0) {
            throw new IllegalArgumentException("logMaxBytes must be positive");
        }
        if (scriptMaxBytes <= 0) {
            throw new IllegalArgumentException("scriptMaxBytes must be positive");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be at least 1");
        }
        if (maxTimeoutSeconds < timeoutSeconds) {
            throw new IllegalArgumentException("maxTimeoutSeconds must be >= timeoutSeconds");
        }
    }

    private static void requireQuantity(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank Kubernetes quantity");
        }
    }
}
