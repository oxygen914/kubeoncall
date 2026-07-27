package com.kubeoncall.sandbox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;

/**
 * Isolated MinIO store for sandbox artifacts (§6.3, SBX-05). Object keys are derived server-side
 * from the run id, artifact type and a server-generated filename — a caller can never supply a raw
 * key, so object-key traversal is impossible by construction. Every write is streamed through a
 * bounded SHA-256 digesting stream that rejects content exceeding the configured per-type byte
 * ceiling, and the MIME type must be on a fixed allowlist. Bodies, pre-signed URLs and raw logs are
 * never logged.
 *
 * <p>The store deliberately does not reuse {@code KnowledgeObjectStorageService} method names: the
 * knowledge store carries import/rollback semantics that do not apply to untrusted sandbox output,
 * and conflating them would let a knowledge janitor delete sandbox evidence or vice versa.
 */
@Service
public class SandboxArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(SandboxArtifactStore.class);

    private static final String PREFIX = "sandbox/";
    private static final Set<String> MIME_ALLOWLIST = Set.of(
            "application/json",
            "application/x-ndjson",
            "text/plain",
            "text/plain; charset=utf-8",
            "application/yaml",
            "application/octet-stream");

    private final MinioClient minioClient;
    private final KubeOnCallProperties properties;

    @Autowired
    public SandboxArtifactStore(MinioClient minioClient, KubeOnCallProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /**
     * Stores an artifact, returning its reference and SHA-256. The object key is
     * {@code sandbox/{runPublicId}/{type}/{filename}}; {@code filename} is sanitized to a flat
     * basename so no path component can escape the prefix.
     *
     * @throws SandboxArtifactException when the content exceeds the per-type ceiling, the MIME type
     *     is not allowed, the bucket is unconfigured, or MinIO rejects the write.
     */
    public StoredArtifact store(
            String runPublicId,
            SandboxArtifactType type,
            String filename,
            String contentType,
            InputStream content,
            SandboxClassification classification) {
        String bucket = requireBucket();
        String key = objectKey(runPublicId, type, filename);
        String mime = normalizeMime(contentType);
        if (!MIME_ALLOWLIST.contains(mime)) {
            throw new SandboxArtifactException("unsupported content type: " + contentType);
        }
        long maxBytes = maxBytesFor(type);
        // Read into a bounded buffer first so the size ceiling is enforced before any byte reaches
        // MinIO: streaming the digest into putObject lets OkHttp swallow a mid-stream abort and
        // upload a truncated object, which would defeat the ceiling. Sandbox artifacts are small and
        // individually capped, so buffering a single upload is safe and authoritative.
        byte[] bytes;
        String sha256;
        try {
            BoundedDigest read = BoundedDigest.read(content, maxBytes);
            bytes = read.bytes();
            sha256 = read.hexDigest();
        } catch (BoundedDigest.TooLargeException ex) {
            throw new SandboxArtifactException("artifact exceeds " + type + " ceiling " + maxBytes + " bytes");
        } catch (IOException ex) {
            throw new SandboxArtifactException("failed to read sandbox artifact", ex);
        }
        long size = bytes.length;
        try {
            minioClient.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(bytes), size, -1)
                            .contentType(mime)
                            .build());
        } catch (Exception ex) {
            throw new SandboxArtifactException("failed to store sandbox artifact", ex);
        }
        log.debug(
                "stored sandbox artifact bucket={} type={} size={} classification={}",
                bucket,
                type,
                size,
                classification);
        return new StoredArtifact(bucket, key, mime, size, sha256);
    }

    /** Convenience overload for in-memory content. */
    public StoredArtifact store(
            String runPublicId,
            SandboxArtifactType type,
            String filename,
            String contentType,
            byte[] content,
            SandboxClassification classification) {
        byte[] bytes = content == null ? new byte[0] : content;
        return store(runPublicId, type, filename, contentType, new ByteArrayInputStream(bytes), classification);
    }

    /**
     * Issues a short-lived pre-signed GET URL for a single object. The URL is returned to the caller
     * for immediate use and must never be persisted or logged.
     */
    public URL presignedGetUrl(String bucket, String objectKey, Duration ttl) {
        requireBucketReference(bucket, objectKey);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (ttl.toSeconds() > 300) {
            throw new IllegalArgumentException("presigned ttl must not exceed 5 minutes");
        }
        try {
            return new URL(minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .build()));
        } catch (Exception ex) {
            throw new SandboxArtifactException("failed to presign sandbox artifact url", ex);
        }
    }

    /** Deletes a single object; idempotent — a missing object is a success. */
    public boolean delete(String bucket, String objectKey) {
        requireBucketReference(bucket, objectKey);
        try {
            minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception ex) {
            log.warn(
                    "sandbox artifact delete failed: bucket={}, errorType={}",
                    bucket,
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Lists object keys under a run prefix for the cleanup janitor. Only keys starting with the
     * canonical {@code sandbox/{runPublicId}/} prefix are returned, so a janitor can never be pointed
     * at an arbitrary prefix by a malformed request.
     */
    public List<String> listObjectsForRun(String runPublicId) {
        String bucket = requireBucket();
        String prefix = runPrefix(runPublicId);
        java.util.List<String> keys = new java.util.ArrayList<>();
        try {
            Iterable<io.minio.Result<io.minio.messages.Item>> objects =
                    minioClient.listObjects(io.minio.ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .recursive(true)
                            .build());
            for (io.minio.Result<io.minio.messages.Item> result : objects) {
                String name = result.get().objectName();
                if (name != null && name.startsWith(prefix)) {
                    keys.add(name);
                }
            }
        } catch (Exception ex) {
            throw new SandboxArtifactException("failed to list sandbox artifacts", ex);
        }
        return List.copyOf(keys);
    }

    private String requireBucket() {
        String bucket = properties.getStorage().getMinio().getBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new SandboxArtifactException("MinIO bucket is not configured");
        }
        return bucket;
    }

    /**
     * Validates a stored artifact reference for read/delete. The bucket must match the configured
     * sandbox bucket (the shared MinIO credentials may access other buckets, so a malformed metadata
     * record must not authorize or delete objects outside the sandbox) and the object key must start
     * with the canonical {@code sandbox/} prefix.
     */
    private void requireBucketReference(String bucket, String objectKey) {
        if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("bucket and objectKey are required");
        }
        if (!bucket.equals(requireBucket())) {
            throw new IllegalArgumentException("bucket is not the configured sandbox bucket");
        }
        if (!objectKey.startsWith(PREFIX)) {
            throw new IllegalArgumentException("object key must be inside the sandbox prefix");
        }
    }

    /**
     * Validates a run public id before it is embedded in an object prefix. The id must match the
     * canonical {@code sbx_<hex>} form the repository generates, so it can never contain a path
     * separator and two runs can never create nested prefixes that a cleanup could confuse.
     */
    static String requireRunId(String runPublicId) {
        if (runPublicId == null || !RUN_ID_PATTERN.matcher(runPublicId).matches()) {
            throw new IllegalArgumentException("runPublicId must be sbx_<hex>");
        }
        return runPublicId;
    }

    private static final java.util.regex.Pattern RUN_ID_PATTERN =
            java.util.regex.Pattern.compile("^sbx_[0-9a-f]{1,128}$");

    /** Canonical key: sandbox/{runPublicId}/{typeFolder}/{flatFilename}. */
    static String objectKey(String runPublicId, SandboxArtifactType type, String filename) {
        requireRunId(runPublicId);
        if (type == null) {
            throw new IllegalArgumentException("artifact type is required");
        }
        String flat = flatFilename(filename);
        return PREFIX + runPublicId + "/" + folderFor(type) + "/" + flat;
    }

    /** Plural folder name per §6.3: inputs/outputs/logs/reports. */
    static String folderFor(SandboxArtifactType type) {
        return switch (type) {
            case INPUT -> "inputs";
            case OUTPUT -> "outputs";
            case LOG -> "logs";
            case REPORT -> "reports";
        };
    }

    private static String runPrefix(String runPublicId) {
        requireRunId(runPublicId);
        return PREFIX + runPublicId + "/";
    }

    /**
     * Reduces a caller-supplied filename to a flat basename: strips any path component and replaces
     * anything outside [A-Za-z0-9._-] with {@code -}. The result can never contain {@code /} or
     * {@code ..}, so it cannot escape the {@code sandbox/{run}/{type}/} prefix.
     */
    static String flatFilename(String filename) {
        String candidate = filename == null ? "" : filename.replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        String basename = slash >= 0 ? candidate.substring(slash + 1) : candidate;
        String normalized = basename.replaceAll("[^A-Za-z0-9._-]+", "-");
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
            normalized = "artifact";
        }
        return normalized;
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private long maxBytesFor(SandboxArtifactType type) {
        KubeOnCallProperties.Sandbox sandbox = properties.getSandbox();
        return switch (type) {
            case INPUT -> sandbox.getInputMaxBytes();
            case OUTPUT -> sandbox.getOutputMaxBytes();
            case LOG -> sandbox.getLogMaxBytes();
            case REPORT -> sandbox.getOutputMaxBytes();
        };
    }

    /** Reference to a stored artifact, including the SHA-256 the caller must persist. */
    public record StoredArtifact(String bucket, String objectKey, String contentType, long sizeBytes, String sha256) {}

    /** Raised when an artifact write or read violates a size, MIME or storage invariant. */
    public static final class SandboxArtifactException extends RuntimeException {
        public SandboxArtifactException(String message) {
            super(message);
        }

        public SandboxArtifactException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Reads an input stream fully into a byte array while digesting it with SHA-256, throwing
     * {@link TooLargeException} as soon as the configured ceiling is exceeded — before any byte is
     * handed to MinIO. The buffer grows to at most {@code maxBytes + 1} so a single byte over the
     * limit is detected without loading an unbounded payload.
     */
    static final class BoundedDigest {

        private final byte[] bytes;
        private final String hexDigest;

        private BoundedDigest(byte[] bytes, String hexDigest) {
            this.bytes = bytes;
            this.hexDigest = hexDigest;
        }

        static BoundedDigest read(InputStream input, long maxBytes) throws IOException, TooLargeException {
            try (input) {
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException ex) {
                    throw new IllegalStateException("SHA-256 not available", ex);
                }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8 * 1024];
                int read;
                while ((read = input.read(chunk)) > 0) {
                    if ((long) buffer.size() + read > maxBytes) {
                        throw new TooLargeException();
                    }
                    buffer.write(chunk, 0, read);
                    digest.update(chunk, 0, read);
                }
                return new BoundedDigest(buffer.toByteArray(), HexFormat.of().formatHex(digest.digest()));
            }
        }

        byte[] bytes() {
            return bytes;
        }

        String hexDigest() {
            return hexDigest;
        }

        /** Signal that the input exceeded the configured byte ceiling. */
        static final class TooLargeException extends Exception {
            TooLargeException() {
                super(null, null, false, false);
            }
        }
    }
}
