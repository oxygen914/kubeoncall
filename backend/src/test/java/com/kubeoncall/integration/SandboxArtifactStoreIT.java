package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.SandboxArtifactStore;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;

/**
 * Real-MinIO verification of {@link SandboxArtifactStore}: size ceiling, SHA-256, MIME allowlist,
 * object-key isolation, presigned URL and delete idempotency. Mirrors the
 * {@code MinioKnowledgeObjectStorageIT} setup against {@code localhost:9000}.
 */
class SandboxArtifactStoreIT {

    private static final String BUCKET = "kubeoncall-it";

    private MinioClient minioClient;
    private SandboxArtifactStore store;
    private KubeOnCallProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        minioClient = MinioClient.builder()
                .endpoint("http://localhost:9000")
                .credentials("minioadmin", "minioadmin")
                .build();
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
        properties = new KubeOnCallProperties();
        properties.getStorage().getMinio().setBucket(BUCKET);
        // Use a small input ceiling so the size-limit test stays fast.
        properties.getSandbox().setInputMaxBytes(8L);
        properties.getSandbox().setOutputMaxBytes(64L);
        properties.getSandbox().setLogMaxBytes(32L);
        store = new SandboxArtifactStore(minioClient, properties);
    }

    @Test
    void storeShouldWriteObjectWithSha256AndCanonicalKey() throws Exception {
        String runId = "sbx_" + UUID.randomUUID().toString().replace("-", "");
        byte[] content = "{\"finding\":\"oom\"}".getBytes(StandardCharsets.UTF_8);
        SandboxArtifactStore.StoredArtifact artifact = store.store(
                runId,
                SandboxArtifactType.OUTPUT,
                "diagnosis.json",
                "application/json",
                content,
                SandboxClassification.UNTRUSTED);

        assertThat(artifact.bucket()).isEqualTo(BUCKET);
        assertThat(artifact.objectKey()).isEqualTo("sandbox/" + runId + "/outputs/diagnosis.json");
        assertThat(artifact.sizeBytes()).isEqualTo(content.length);
        assertThat(artifact.sha256()).isEqualTo(sha256(content));

        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(artifact.bucket())
                .object(artifact.objectKey())
                .build())) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        } finally {
            cleanup(artifact.objectKey());
        }
    }

    @Test
    void storeShouldRejectContentExceedingTypeCeiling() {
        String runId = "sbx_" + UUID.randomUUID().toString().replace("-", "");
        // inputMaxBytes is 8; this payload is larger.
        byte[] oversize = "this-is-definitely-longer-than-eight-bytes".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.store(
                        runId,
                        SandboxArtifactType.INPUT,
                        "evidence.json",
                        "application/json",
                        oversize,
                        SandboxClassification.INTERNAL))
                .isInstanceOf(SandboxArtifactStore.SandboxArtifactException.class)
                .hasMessageContaining("exceeds");

        // Nothing was written for the rejected artifact.
        assertThat(store.listObjectsForRun(runId)).isEmpty();
    }

    @Test
    void storeShouldRejectDisallowedMimeType() {
        String runId = "sbx_" + UUID.randomUUID().toString().replace("-", "");
        assertThatThrownBy(() -> store.store(
                        runId,
                        SandboxArtifactType.INPUT,
                        "evil.exe",
                        "application/x-msdownload",
                        new byte[] {1},
                        SandboxClassification.UNTRUSTED))
                .isInstanceOf(SandboxArtifactStore.SandboxArtifactException.class)
                .hasMessageContaining("unsupported content type");
    }

    @Test
    void storeShouldFlattenTraversalFilenamesIntoCanonicalKey() {
        String runId = "sbx_" + UUID.randomUUID().toString().replace("-", "");
        SandboxArtifactStore.StoredArtifact artifact = store.store(
                runId,
                SandboxArtifactType.LOG,
                "../../../etc/passwd",
                "text/plain",
                new byte[] {'x'},
                SandboxClassification.UNTRUSTED);

        // The traversal attempt is flattened to its last path component; the object lands inside the
        // run's logs prefix and contains no "..".
        assertThat(artifact.objectKey()).isEqualTo("sandbox/" + runId + "/logs/passwd");
        assertThat(artifact.objectKey()).doesNotContain("..");
        cleanup(artifact.objectKey());
    }

    @Test
    void presignedGetUrlShouldBeShortLivedAndScopedToSandboxPrefix() throws Exception {
        String runId = "sbx_" + UUID.randomUUID().toString().replace("-", "");
        SandboxArtifactStore.StoredArtifact artifact = store.store(
                runId,
                SandboxArtifactType.OUTPUT,
                "out.json",
                "application/json",
                "{}".getBytes(StandardCharsets.UTF_8),
                SandboxClassification.UNTRUSTED);
        try {
            java.net.URL url =
                    store.presignedGetUrl(artifact.bucket(), artifact.objectKey(), java.time.Duration.ofMinutes(2));
            assertThat(url.toString()).contains("sandbox/" + runId + "/outputs/out.json");

            // A ttl over 5 minutes is rejected.
            assertThatThrownBy(() -> store.presignedGetUrl(
                            artifact.bucket(), artifact.objectKey(), java.time.Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class);
            // An object key outside the sandbox prefix is rejected.
            assertThatThrownBy(() -> store.presignedGetUrl(
                            artifact.bucket(), "knowledge/imports/x", java.time.Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            cleanup(artifact.objectKey());
        }
    }

    @Test
    void deleteShouldBeIdempotentAndListShouldOnlyReturnRunPrefix() {
        String runId = "sbx_" + UUID.randomUUID().toString().replace("-", "");
        SandboxArtifactStore.StoredArtifact artifact = store.store(
                runId,
                SandboxArtifactType.REPORT,
                "report.json",
                "application/json",
                new byte[] {'1'},
                SandboxClassification.INTERNAL);

        assertThat(store.listObjectsForRun(runId)).contains(artifact.objectKey());
        assertThat(store.delete(artifact.bucket(), artifact.objectKey())).isTrue();
        // Second delete is a no-op success (missing object).
        assertThat(store.delete(artifact.bucket(), artifact.objectKey())).isTrue();
        assertThat(store.listObjectsForRun(runId)).doesNotContain(artifact.objectKey());
    }

    private void cleanup(String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
