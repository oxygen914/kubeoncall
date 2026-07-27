package com.kubeoncall.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.observability.DependencyCircuitBreaker;

/**
 * Bounded authenticated client for the private Sandbox Controller API.
 *
 * <p>It only sends a server-derived projection of a persisted run. HMAC signing is compatible with
 * the independent Go controller: method, path, timestamp, nonce and SHA-256(body) are signed in
 * that order. Response bodies are bounded before parsing and are never included in exceptions.
 */
@Component
public class SandboxControllerClient {

    static final String DEPENDENCY = "sandbox_controller";
    private static final String CREATE_PATH = "/internal/v1/runs";
    private static final String SIMULATION_PATH = "/internal/v1/simulations";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KubeOnCallProperties properties;
    private final DependencyCircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final SandboxArtifactStore artifactStore;

    public SandboxControllerClient(
            KubeOnCallProperties properties,
            DependencyCircuitBreaker circuitBreaker,
            ObjectMapper objectMapper,
            SandboxArtifactStore artifactStore) {
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
        this.artifactStore = artifactStore;
    }

    /** Performs one idempotent create-or-return call; it never polls a Job. */
    public DispatchResult dispatch(SandboxRunRecord run) {
        if (run == null) {
            throw new IllegalArgumentException("sandbox run is required");
        }
        DispatchResult result = circuitBreaker.execute(DEPENDENCY, () -> send(run));
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new SandboxControllerClientException(
                    "CONTROLLER_HTTP_" + result.statusCode(), false, result.statusCode());
        }
        if (!run.publicId().equals(result.controllerRunId())) {
            throw new SandboxControllerClientException("CONTROLLER_RUN_ID_MISMATCH", false, result.statusCode());
        }
        return result;
    }

    /** Reads one normalized controller status; it never waits for a Job transition. */
    public ControllerStatus status(SandboxRunRecord run) {
        if (run == null) {
            throw new IllegalArgumentException("sandbox run is required");
        }
        return lifecycle("GET", lifecyclePath(run) + "/" + run.publicId(), run);
    }

    /** Requests asynchronous Job cancellation and returns the Controller's immediate status. */
    public ControllerStatus cancel(SandboxRunRecord run) {
        if (run == null) {
            throw new IllegalArgumentException("sandbox run is required");
        }
        return lifecycle("DELETE", lifecyclePath(run) + "/" + run.publicId(), run);
    }

    /** Collects the controller's normalized bounded result once a Job is terminal. */
    public CollectedResult collect(SandboxRunRecord run) {
        if (run == null) {
            throw new IllegalArgumentException("sandbox run is required");
        }
        ControllerResponse response =
                call("GET", lifecyclePath(run) + "/" + run.publicId() + "/logs", null, run.requestId());
        Map<String, Object> payload = response.payload();
        requireMatchingRunId(run.publicId(), payload, response.statusCode());
        return new CollectedResult(
                run.publicId(),
                stringValue(payload.get("phase")),
                numberValue(payload.get("exitCode")),
                stringValue(payload.get("reason")),
                stringValue(payload.get("logs")),
                stringValue(payload.get("output")),
                Boolean.TRUE.equals(payload.get("outputFound")));
    }

    private ControllerStatus lifecycle(String method, String path, SandboxRunRecord run) {
        ControllerResponse response = call(method, path, null, run.requestId());
        Map<String, Object> payload = response.payload();
        requireMatchingRunId(run.publicId(), payload, response.statusCode());
        return new ControllerStatus(
                run.publicId(), stringValue(payload.get("phase")), Boolean.TRUE.equals(payload.get("exists")));
    }

    private DispatchResult send(SandboxRunRecord run) {
        KubeOnCallProperties.Sandbox sandbox = properties.getSandbox();
        URI endpoint = endpoint(sandbox.getControllerEndpoint());
        String secret = requireText(sandbox.getControllerHmacSecret(), "CONTROLLER_AUTH_NOT_CONFIGURED");
        String keyId = requireText(sandbox.getControllerKeyId(), "CONTROLLER_AUTH_NOT_CONFIGURED");
        byte[] body = requestBody(run);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String path = lifecyclePath(run);
        String signature = signature("POST", path, timestamp, nonce, body, secret);
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(java.time.Duration.ofMillis(sandbox.getControllerReadTimeoutMillis()))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", safeRequestId(run.requestId()))
                .header("X-Sandbox-Key-Id", keyId)
                .header("X-Sandbox-Timestamp", timestamp)
                .header("X-Sandbox-Nonce", nonce)
                .header("X-Sandbox-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(sandbox.getControllerConnectTimeoutMillis()))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                drainBounded(response.body(), sandbox.getControllerMaxResponseBytes());
                if (isRetryableStatus(status)) {
                    throw new SandboxControllerClientException("CONTROLLER_HTTP_" + status, true, status);
                }
                return new DispatchResult("", "", status);
            }
            Map<String, Object> payload = parseBounded(response.body(), sandbox.getControllerMaxResponseBytes());
            String controllerRunId = stringValue(payload.get("runId"));
            if (controllerRunId.isBlank()) {
                // The Run ID is caller-owned and idempotent. Older controller builds serialize Go
                // fields as RunID, so preserve backwards compatibility without trusting other data.
                controllerRunId = stringValue(payload.get("RunID"));
            }
            if (controllerRunId.isBlank()) {
                controllerRunId = run.publicId();
            }
            return new DispatchResult(controllerRunId, stringValue(payload.get("phase")), status);
        } catch (SandboxControllerClientException ex) {
            throw ex;
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new SandboxControllerClientException("CONTROLLER_TIMEOUT", true, 0, ex);
        } catch (IOException ex) {
            throw new SandboxControllerClientException("CONTROLLER_IO_FAILURE", true, 0, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SandboxControllerClientException("CONTROLLER_INTERRUPTED", true, 0, ex);
        }
    }

    private ControllerResponse call(String method, String path, byte[] body, String requestId) {
        KubeOnCallProperties.Sandbox sandbox = properties.getSandbox();
        URI endpoint = endpoint(sandbox.getControllerEndpoint());
        String secret = requireText(sandbox.getControllerHmacSecret(), "CONTROLLER_AUTH_NOT_CONFIGURED");
        String keyId = requireText(sandbox.getControllerKeyId(), "CONTROLLER_AUTH_NOT_CONFIGURED");
        byte[] safeBody = body == null ? new byte[0] : body;
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = signature(method, path, timestamp, nonce, safeBody, secret);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(java.time.Duration.ofMillis(sandbox.getControllerReadTimeoutMillis()))
                .header("X-Request-Id", safeRequestId(requestId))
                .header("X-Sandbox-Key-Id", keyId)
                .header("X-Sandbox-Timestamp", timestamp)
                .header("X-Sandbox-Nonce", nonce)
                .header("X-Sandbox-Signature", signature);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofByteArray(safeBody))
                .build();
        try {
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(sandbox.getControllerConnectTimeoutMillis()))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                drainBounded(response.body(), sandbox.getControllerMaxResponseBytes());
                if (isRetryableStatus(status)) {
                    throw new SandboxControllerClientException("CONTROLLER_HTTP_" + status, true, status);
                }
                throw new SandboxControllerClientException("CONTROLLER_HTTP_" + status, false, status);
            }
            return new ControllerResponse(
                    status, parseBounded(response.body(), sandbox.getControllerMaxResponseBytes()));
        } catch (SandboxControllerClientException ex) {
            throw ex;
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new SandboxControllerClientException("CONTROLLER_TIMEOUT", true, 0, ex);
        } catch (IOException ex) {
            throw new SandboxControllerClientException("CONTROLLER_IO_FAILURE", true, 0, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SandboxControllerClientException("CONTROLLER_INTERRUPTED", true, 0, ex);
        }
    }

    private byte[] requestBody(SandboxRunRecord run) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", run.publicId());
            payload.put("toolId", run.toolId());
            payload.put("toolVersion", run.toolVersion());
            payload.put("inputArtifactUri", inputArtifactUri(run));
            payload.put(
                    "labels",
                    Map.of("sandbox.kubeoncall.io/mode", run.mode().name().toLowerCase()));
            payload.put("expiresAt", run.expiresAt().toString());
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new SandboxControllerClientException("CONTROLLER_REQUEST_ENCODE_FAILED", false, 0, ex);
        }
    }

    private String inputArtifactUri(SandboxRunRecord run) {
        String bucket = properties.getStorage().getMinio().getBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new SandboxControllerClientException("SANDBOX_ARTIFACT_BUCKET_NOT_CONFIGURED", false, 0);
        }
        String inputFilename =
                switch (run.mode()) {
                    case GENERATED_CODE -> "generated-code.json";
                    case MANIFEST_VALIDATION -> "manifest-validation.json";
                    case REMEDIATION_SIMULATION -> "remediation-simulation.json";
                    default -> "evidence.json";
                };
        String objectKey = SandboxArtifactStore.objectKey(
                run.publicId(), com.kubeoncall.sandbox.domain.SandboxArtifactType.INPUT, inputFilename);
        // A Job receives one short-lived, read-only artifact capability rather than MinIO access
        // credentials. The capability is sent only in the signed Controller request and is neither
        // stored in MySQL nor written to logs.
        return artifactStore
                .presignedGetUrl(bucket, objectKey, Duration.ofMinutes(5))
                .toString();
    }

    private Map<String, Object> parseBounded(InputStream body, long maxBytes) {
        try {
            byte[] bytes = bounded(body, maxBytes);
            return objectMapper.readValue(bytes, MAP_TYPE);
        } catch (SandboxControllerClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SandboxControllerClientException("CONTROLLER_INVALID_RESPONSE", true, 0, ex);
        }
    }

    private void drainBounded(InputStream body, long maxBytes) {
        try {
            bounded(body, maxBytes);
        } catch (Exception ignored) {
            // The HTTP status is authoritative; error bodies are intentionally discarded.
        }
    }

    private static byte[] bounded(InputStream stream, long maxBytes) throws IOException {
        if (stream == null || maxBytes < 1) {
            throw new SandboxControllerClientException("CONTROLLER_RESPONSE_TOO_LARGE", true, 0);
        }
        try (stream) {
            byte[] bytes = stream.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
            if (bytes.length > maxBytes) {
                throw new SandboxControllerClientException("CONTROLLER_RESPONSE_TOO_LARGE", true, 0);
            }
            return bytes;
        }
    }

    private static URI endpoint(String value) {
        try {
            URI endpoint = URI.create(requireText(value, "CONTROLLER_ENDPOINT_NOT_CONFIGURED"));
            if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("unsupported scheme");
            }
            return endpoint.resolve("/");
        } catch (IllegalArgumentException ex) {
            throw new SandboxControllerClientException("CONTROLLER_ENDPOINT_NOT_CONFIGURED", false, 0, ex);
        }
    }

    private static String lifecyclePath(SandboxRunRecord run) {
        return run.mode() == com.kubeoncall.sandbox.domain.SandboxRunMode.REMEDIATION_SIMULATION
                ? SIMULATION_PATH
                : CREATE_PATH;
    }

    private static String requireText(String value, String code) {
        if (value == null || value.isBlank()) {
            throw new SandboxControllerClientException(code, false, 0);
        }
        return value.trim();
    }

    private static String safeRequestId(String requestId) {
        return requestId == null || requestId.isBlank()
                ? "sandbox-dispatch"
                : requestId.substring(0, Math.min(128, requestId.length()));
    }

    private static String signature(
            String method, String path, String timestamp, String nonce, byte[] body, String secret) {
        try {
            String canonical = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void requireMatchingRunId(String expected, Map<String, Object> payload, int statusCode) {
        String actual = stringValue(payload.get("runId"));
        if (actual.isBlank()) {
            actual = stringValue(payload.get("RunID"));
        }
        if (!expected.equals(actual)) {
            throw new SandboxControllerClientException("CONTROLLER_RUN_ID_MISMATCH", false, statusCode);
        }
    }

    public record DispatchResult(String controllerRunId, String phase, int statusCode) {}

    public record ControllerStatus(String runId, String phase, boolean exists) {}

    public record CollectedResult(
            String runId,
            String phase,
            Integer exitCode,
            String reason,
            String logs,
            String output,
            boolean outputFound) {
        public CollectedResult(
                String runId, String phase, Integer exitCode, String reason, String logs, boolean outputFound) {
            this(runId, phase, exitCode, reason, logs, "", outputFound);
        }
    }

    private record ControllerResponse(int statusCode, Map<String, Object> payload) {}
}
