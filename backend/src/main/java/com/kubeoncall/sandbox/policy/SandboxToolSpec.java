package com.kubeoncall.sandbox.policy;

import java.util.Objects;

import com.kubeoncall.sandbox.domain.SandboxRunMode;

/**
 * Server-side definition of a sandbox tool (§6.1). The tool catalog is the only place that fixes
 * the runtime image, entrypoint, input/output schemas, resource limits and network posture for a
 * mode; a caller may reference a tool only by {@code id} and {@code version} and may never supply
 * an image. The image reference must be a pinned digest ({@code <repo>@sha256:<hex>}) — tags and
 * {@code latest} are rejected at construction so a supply-chain substitution can never reach a run.
 *
 * <p>{@link #networkEgressPolicy} is one of {@link NetworkEgressPolicy} and defaults to
 * {@link NetworkEgressPolicy#DENY_ALL}: a tool must opt into egress explicitly, and even then only
 * DNS plus a restricted artifact channel are permitted.
 */
public record SandboxToolSpec(
        String id,
        String version,
        SandboxRunMode mode,
        String imageDigest,
        String entrypoint,
        SandboxResourceLimits resourceLimits,
        NetworkEgressPolicy networkEgressPolicy,
        String inputSchemaRef,
        String outputSchemaRef) {

    /** Network posture a tool may request. The default for every tool is {@link #DENY_ALL}. */
    public enum NetworkEgressPolicy {
        DENY_ALL,
        DNS_AND_ARTIFACT_CHANNEL
    }

    public SandboxToolSpec {
        Objects.requireNonNull(mode, "mode");
        requireId("id", id);
        requireId("version", version);
        requireDigest(imageDigest);
        Objects.requireNonNull(resourceLimits, "resourceLimits");
        Objects.requireNonNull(networkEgressPolicy, "networkEgressPolicy");
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("entrypoint must be non-blank");
        }
        networkEgressPolicy = networkEgressPolicy == null ? NetworkEgressPolicy.DENY_ALL : networkEgressPolicy;
    }

    /** True when the image reference is a digest-pinned form acceptable for a sandbox tool. */
    public static boolean isDigestPinned(String image) {
        if (image == null || image.isBlank()) {
            return false;
        }
        // Reject tags and latest explicitly; require a @sha256: digest suffix. The hex digest must
        // be at least 32 characters (SHA-256 is 64, but we only enforce the digest-suffix shape
        // here — the catalog loader does a fuller check).
        if (image.endsWith(":latest") || image.contains(":latest@")) {
            return false;
        }
        int at = image.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String digest = image.substring(at + 1);
        if (!digest.startsWith("sha256:")) {
            return false;
        }
        String hex = digest.substring("sha256:".length());
        return hex.length() >= 32 && hex.matches("[0-9a-fA-F]+");
    }

    private static void requireId(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }

    private static void requireDigest(String image) {
        if (!isDigestPinned(image)) {
            throw new IllegalArgumentException("image must be pinned by digest (<repo>@sha256:<hex>), got: " + image);
        }
    }
}
