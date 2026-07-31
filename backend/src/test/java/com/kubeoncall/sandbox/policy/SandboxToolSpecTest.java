package com.kubeoncall.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kubeoncall.sandbox.domain.SandboxRunMode;

/** Validates the digest-pinning, resource-bound and network-posture invariants of {@link SandboxToolSpec}. */
class SandboxToolSpecTest {

    private static final String VALID_DIGEST = "kubeoncall/sandbox-log-analyzer@sha256:"
            + "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    private static SandboxResourceLimits limits() {
        return new SandboxResourceLimits("1", "1Gi", "2Gi", 1024L, 1024L, 1024L, 1024L, 300, 900);
    }

    @Test
    void shouldAcceptDigestPinnedImage() {
        assertDoesNotThrow(() -> new SandboxToolSpec(
                "log-analyzer",
                "1.0.0",
                SandboxRunMode.FIXED_DIAGNOSTIC,
                VALID_DIGEST,
                "/entrypoint.sh",
                limits(),
                SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                "input.json",
                "output.json"));
    }

    @Test
    void shouldRejectTaggedImage() {
        assertFalse(SandboxToolSpec.isDigestPinned("kubeoncall/sandbox-log-analyzer:1.0.0"));
        assertFalse(SandboxToolSpec.isDigestPinned("kubeoncall/sandbox-log-analyzer:latest"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxToolSpec(
                        "log-analyzer",
                        "1.0.0",
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        "kubeoncall/sandbox-log-analyzer:latest",
                        "/e.sh",
                        limits(),
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
    }

    @Test
    void shouldRejectImageWithoutDigest() {
        assertFalse(SandboxToolSpec.isDigestPinned("kubeoncall/sandbox-log-analyzer"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxToolSpec(
                        "log-analyzer",
                        "1.0.0",
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        "kubeoncall/sandbox-log-analyzer",
                        "/e.sh",
                        limits(),
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
    }

    @Test
    void shouldRejectNonSha256Digest() {
        assertFalse(SandboxToolSpec.isDigestPinned("repo@sha1:abcdef"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxToolSpec(
                        "t",
                        "1",
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        "repo@sha1:abcdef",
                        "/e.sh",
                        limits(),
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
    }

    @Test
    void shouldRejectBlankRequiredFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxToolSpec(
                        "",
                        "1",
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        VALID_DIGEST,
                        "/e.sh",
                        limits(),
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxToolSpec(
                        "t",
                        "",
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        VALID_DIGEST,
                        "/e.sh",
                        limits(),
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxToolSpec(
                        "t",
                        "1",
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        VALID_DIGEST,
                        "  ",
                        limits(),
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
    }

    @Test
    void shouldRejectNonPositiveResourceLimits() {
        // Output budget of zero would create an unbounded-output run.
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxResourceLimits("1", "1Gi", "2Gi", 1024L, 0L, 1024L, 1024L, 300, 900));
        // Timeout below 1s.
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxResourceLimits("1", "1Gi", "2Gi", 1024L, 1024L, 1024L, 1024L, 0, 900));
        // maxTimeout below timeout.
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxResourceLimits("1", "1Gi", "2Gi", 1024L, 1024L, 1024L, 1024L, 300, 120));
        // Blank CPU quantity.
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxResourceLimits("", "1Gi", "2Gi", 1024L, 1024L, 1024L, 1024L, 300, 900));
    }

    @Test
    void shouldRejectNullModeOrLimits() {
        assertThrows(
                NullPointerException.class,
                () -> new SandboxToolSpec(
                        "t",
                        "1",
                        null,
                        VALID_DIGEST,
                        "/e.sh",
                        limits(),
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
        assertThrows(
                NullPointerException.class,
                () -> new SandboxToolSpec(
                        "t",
                        "1",
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        VALID_DIGEST,
                        "/e.sh",
                        null,
                        SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                        "in",
                        "out"));
    }

    @Test
    void digestPinCheckShouldAcceptMinimumLengthHex() {
        // 32 hex chars is the minimum accepted digest-pinned form.
        assertTrue(SandboxToolSpec.isDigestPinned("repo@sha256:0123456789abcdef0123456789abcdef"));
    }
}
