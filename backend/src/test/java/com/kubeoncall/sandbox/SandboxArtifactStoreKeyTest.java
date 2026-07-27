package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.kubeoncall.sandbox.domain.SandboxArtifactType;

/** Pure-function coverage of {@link SandboxArtifactStore} key derivation and filename flattening. */
class SandboxArtifactStoreKeyTest {

    @Test
    void objectKeyShouldFollowCanonicalPrefix() {
        assertThat(SandboxArtifactStore.objectKey("sbx_123", SandboxArtifactType.INPUT, "evidence.json"))
                .isEqualTo("sandbox/sbx_123/inputs/evidence.json");
        assertThat(SandboxArtifactStore.objectKey("sbx_123", SandboxArtifactType.OUTPUT, "diagnosis.json"))
                .isEqualTo("sandbox/sbx_123/outputs/diagnosis.json");
        assertThat(SandboxArtifactStore.objectKey("sbx_123", SandboxArtifactType.LOG, "run.log"))
                .isEqualTo("sandbox/sbx_123/logs/run.log");
        assertThat(SandboxArtifactStore.objectKey("sbx_123", SandboxArtifactType.REPORT, "report.json"))
                .isEqualTo("sandbox/sbx_123/reports/report.json");
    }

    @Test
    void flatFilenameShouldStripPathComponentsAndRejectTraversal() {
        // The last path component is taken, and any residual non-safe chars are flattened; the key
        // property is the result contains no "/" and no "..".
        assertThat(SandboxArtifactStore.flatFilename("../../../etc/passwd")).isEqualTo("passwd");
        assertThat(SandboxArtifactStore.flatFilename("/tmp/secret.txt")).isEqualTo("secret.txt");
        assertThat(SandboxArtifactStore.flatFilename("a b/c.txt")).isEqualTo("c.txt");
        assertThat(SandboxArtifactStore.flatFilename("")).isEqualTo("artifact");
        assertThat(SandboxArtifactStore.flatFilename(null)).isEqualTo("artifact");
        assertThat(SandboxArtifactStore.flatFilename("..")).isEqualTo("artifact");
        assertThat(SandboxArtifactStore.flatFilename(".")).isEqualTo("artifact");
    }

    @Test
    void flatFilenameShouldNeverContainSlashOrDoubleDot() {
        for (String input : new String[] {"a/../b", "a/./b", "a\\b", "a:b", "a;b"}) {
            String flat = SandboxArtifactStore.flatFilename(input);
            assertThat(flat).doesNotContain("/").doesNotContain("\\");
            assertThat(flat).doesNotContain("..");
        }
    }

    @Test
    void objectKeyShouldRejectBlankRunIdAndNullType() {
        assertThatThrownBy(() -> SandboxArtifactStore.objectKey("", SandboxArtifactType.INPUT, "f"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SandboxArtifactStore.objectKey("sbx_1", null, "f"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void objectKeyShouldRejectRunIdWithPathSeparatorOrNonCanonicalForm() {
        // A run id containing '/' would create a nested prefix and let cleanup of one run touch
        // another's artifacts, so only the canonical sbx_<hex> form is accepted.
        assertThatThrownBy(() -> SandboxArtifactStore.objectKey("sbx_a/inputs", SandboxArtifactType.INPUT, "f"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SandboxArtifactStore.objectKey("a/inputs", SandboxArtifactType.INPUT, "f"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SandboxArtifactStore.objectKey("run-1", SandboxArtifactType.INPUT, "f"))
                .isInstanceOf(IllegalArgumentException.class);
        // Canonical form is accepted and produces a single run segment.
        assertThat(SandboxArtifactStore.objectKey("sbx_abc123", SandboxArtifactType.INPUT, "f.json"))
                .isEqualTo("sandbox/sbx_abc123/inputs/f.json");
    }
}
