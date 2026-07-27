package com.kubeoncall.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.kubeoncall.sandbox.domain.SandboxRunMode;

/**
 * Sandbox capability configuration. Every toggle defaults to off and the deployment-wide hard
 * ceilings are immutable, so introducing this group never changes existing behavior until operators
 * explicitly opt in. Configured values may not exceed the server-side hard limits; {@link #validate()}
 * enforces that contract and is the single place downstream code calls before relying on a bound
 * value. Secrets, the controller endpoint and pre-signed inputs are intentionally never serialized
 * through {@link #limits()} — capabilities describe what the deployment supports, never credentials.
 *
 * <p>CPU, memory and ephemeral storage are stored as opaque Kubernetes quantity strings (e.g.
 * {@code "1"}, {@code "1Gi"}, {@code "2Gi"}); their cross-resource ceiling validation is deferred to
 * the hardened JobSpec builder (SBX-08), which is the single component that parses Kubernetes
 * quantities. This unit validates only the scalar byte/second/count ceilings it can reason about.
 */
class SandboxProperties {

    /** Absolute ceilings this deployment will enforce regardless of operator configuration. */
    static final class HardLimits {

        static final int MAX_TIMEOUT_SECONDS = 900;
        static final int DEFAULT_TIMEOUT_SECONDS = 300;
        static final long MAX_INPUT_BYTES = 50L * 1024 * 1024;
        static final long MAX_OUTPUT_BYTES = 10L * 1024 * 1024;
        static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
        static final long MAX_SCRIPT_BYTES = 256L * 1024;
        static final int MAX_ARTIFACT_RETENTION_HOURS = 24;
        static final int MAX_PER_ALARM_CONCURRENCY = 1;
        static final int MAX_GLOBAL_CONCURRENCY = 4;

        // Kubernetes-quantity ceilings, expressed in the smallest unit each resource is measured in.
        static final long MAX_CPU_MILLICORES = 1000L;
        static final long MAX_MEMORY_BYTES = 1024L * 1024 * 1024;
        static final long MAX_EPHEMERAL_STORAGE_BYTES = 2L * 1024 * 1024 * 1024;

        // Controller-call safety ceilings so a misconfigured client cannot hang the backend or
        // swallow an unbounded response while the sandbox is enabled.
        static final long MAX_CONTROLLER_CONNECT_TIMEOUT_MILLIS = 30_000L;
        static final long MAX_CONTROLLER_READ_TIMEOUT_MILLIS = 60_000L;
        static final long MAX_CONTROLLER_RESPONSE_BYTES = 16L * 1024 * 1024;

        private HardLimits() {}
    }

    private boolean enabled = false;
    private boolean fixedDiagnostic = false;
    private boolean generatedCode = false;
    private boolean manifestValidation = false;
    private boolean remediationSimulation = false;
    private boolean agentAutoRouteEnabled = false;

    private String controllerEndpoint = "";
    private String controllerKeyId = "backend";
    private String controllerHmacSecret = "";
    private long controllerConnectTimeoutMillis = 3000;
    private long controllerReadTimeoutMillis = 5000;
    private long controllerMaxResponseBytes = 2L * 1024 * 1024;

    private int timeoutSeconds = HardLimits.DEFAULT_TIMEOUT_SECONDS;
    private int maxTimeoutSeconds = HardLimits.MAX_TIMEOUT_SECONDS;
    private String cpu = "1";
    private String memory = "1Gi";
    private String ephemeralStorage = "2Gi";
    private long inputMaxBytes = HardLimits.MAX_INPUT_BYTES;
    private long outputMaxBytes = HardLimits.MAX_OUTPUT_BYTES;
    private long logMaxBytes = HardLimits.MAX_LOG_BYTES;
    private long scriptMaxBytes = HardLimits.MAX_SCRIPT_BYTES;
    private int artifactRetentionHours = HardLimits.MAX_ARTIFACT_RETENTION_HOURS;
    private int perAlarmConcurrency = HardLimits.MAX_PER_ALARM_CONCURRENCY;
    private int globalConcurrency = HardLimits.MAX_GLOBAL_CONCURRENCY;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFixedDiagnostic() {
        return fixedDiagnostic;
    }

    public void setFixedDiagnostic(boolean fixedDiagnostic) {
        this.fixedDiagnostic = fixedDiagnostic;
    }

    public boolean isGeneratedCode() {
        return generatedCode;
    }

    public void setGeneratedCode(boolean generatedCode) {
        this.generatedCode = generatedCode;
    }

    public boolean isManifestValidation() {
        return manifestValidation;
    }

    public void setManifestValidation(boolean manifestValidation) {
        this.manifestValidation = manifestValidation;
    }

    public boolean isRemediationSimulation() {
        return remediationSimulation;
    }

    public void setRemediationSimulation(boolean remediationSimulation) {
        this.remediationSimulation = remediationSimulation;
    }

    public boolean isAgentAutoRouteEnabled() {
        return agentAutoRouteEnabled;
    }

    public void setAgentAutoRouteEnabled(boolean agentAutoRouteEnabled) {
        this.agentAutoRouteEnabled = agentAutoRouteEnabled;
    }

    public String getControllerEndpoint() {
        return controllerEndpoint;
    }

    public void setControllerEndpoint(String controllerEndpoint) {
        this.controllerEndpoint = controllerEndpoint;
    }

    public String getControllerKeyId() {
        return controllerKeyId;
    }

    public void setControllerKeyId(String controllerKeyId) {
        this.controllerKeyId = controllerKeyId;
    }

    public String getControllerHmacSecret() {
        return controllerHmacSecret;
    }

    public void setControllerHmacSecret(String controllerHmacSecret) {
        this.controllerHmacSecret = controllerHmacSecret;
    }

    public long getControllerConnectTimeoutMillis() {
        return controllerConnectTimeoutMillis;
    }

    public void setControllerConnectTimeoutMillis(long controllerConnectTimeoutMillis) {
        this.controllerConnectTimeoutMillis = controllerConnectTimeoutMillis;
    }

    public long getControllerReadTimeoutMillis() {
        return controllerReadTimeoutMillis;
    }

    public void setControllerReadTimeoutMillis(long controllerReadTimeoutMillis) {
        this.controllerReadTimeoutMillis = controllerReadTimeoutMillis;
    }

    public long getControllerMaxResponseBytes() {
        return controllerMaxResponseBytes;
    }

    public void setControllerMaxResponseBytes(long controllerMaxResponseBytes) {
        this.controllerMaxResponseBytes = controllerMaxResponseBytes;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }

    public String getCpu() {
        return cpu;
    }

    public void setCpu(String cpu) {
        this.cpu = cpu;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getEphemeralStorage() {
        return ephemeralStorage;
    }

    public void setEphemeralStorage(String ephemeralStorage) {
        this.ephemeralStorage = ephemeralStorage;
    }

    public long getInputMaxBytes() {
        return inputMaxBytes;
    }

    public void setInputMaxBytes(long inputMaxBytes) {
        this.inputMaxBytes = inputMaxBytes;
    }

    public long getOutputMaxBytes() {
        return outputMaxBytes;
    }

    public void setOutputMaxBytes(long outputMaxBytes) {
        this.outputMaxBytes = outputMaxBytes;
    }

    public long getLogMaxBytes() {
        return logMaxBytes;
    }

    public void setLogMaxBytes(long logMaxBytes) {
        this.logMaxBytes = logMaxBytes;
    }

    public long getScriptMaxBytes() {
        return scriptMaxBytes;
    }

    public void setScriptMaxBytes(long scriptMaxBytes) {
        this.scriptMaxBytes = scriptMaxBytes;
    }

    public int getArtifactRetentionHours() {
        return artifactRetentionHours;
    }

    public void setArtifactRetentionHours(int artifactRetentionHours) {
        this.artifactRetentionHours = artifactRetentionHours;
    }

    public int getPerAlarmConcurrency() {
        return perAlarmConcurrency;
    }

    public void setPerAlarmConcurrency(int perAlarmConcurrency) {
        this.perAlarmConcurrency = perAlarmConcurrency;
    }

    public int getGlobalConcurrency() {
        return globalConcurrency;
    }

    public void setGlobalConcurrency(int globalConcurrency) {
        this.globalConcurrency = globalConcurrency;
    }

    /**
     * Whether a specific run mode is permitted by this deployment. A mode is allowed only when the
     * sandbox is globally enabled and its dedicated toggle is on, so disabling the master switch
     * always reverts every capability regardless of per-mode configuration.
     */
    public boolean isModeEnabled(SandboxRunMode mode) {
        if (!enabled) {
            return false;
        }
        return switch (mode) {
            case FIXED_DIAGNOSTIC -> fixedDiagnostic;
            case GENERATED_CODE -> generatedCode;
            case MANIFEST_VALIDATION -> manifestValidation;
            case REMEDIATION_SIMULATION -> remediationSimulation;
        };
    }

    /**
     * Validates configured values against the immutable hard ceilings. Throws
     * {@link IllegalStateException} with every offending field collected, so misconfiguration fails
     * fast at startup rather than silently clamping a production request. CPU, memory and ephemeral
     * storage are parsed as Kubernetes quantities and rejected when malformed or above the hard
     * ceilings; the hardened JobSpec builder (SBX-08) re-validates at render time as defense in
     * depth.
     */
    public void validate() {
        Map<String, String> violations = new LinkedHashMap<>();
        if (maxTimeoutSeconds < 1 || maxTimeoutSeconds > HardLimits.MAX_TIMEOUT_SECONDS) {
            violations.put("maxTimeoutSeconds", "must not exceed hard ceiling " + HardLimits.MAX_TIMEOUT_SECONDS + "s");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > maxTimeoutSeconds) {
            violations.put("timeoutSeconds", "must be between 1 and maxTimeoutSeconds (" + maxTimeoutSeconds + ")");
        }
        if (maxTimeoutSeconds < timeoutSeconds) {
            violations.put("maxTimeoutSeconds", "must be >= timeoutSeconds");
        }
        if (inputMaxBytes < 1 || inputMaxBytes > HardLimits.MAX_INPUT_BYTES) {
            violations.put("inputMaxBytes", "must not exceed hard ceiling " + HardLimits.MAX_INPUT_BYTES + " bytes");
        }
        if (outputMaxBytes < 1 || outputMaxBytes > HardLimits.MAX_OUTPUT_BYTES) {
            violations.put("outputMaxBytes", "must not exceed hard ceiling " + HardLimits.MAX_OUTPUT_BYTES + " bytes");
        }
        if (logMaxBytes < 1 || logMaxBytes > HardLimits.MAX_LOG_BYTES) {
            violations.put("logMaxBytes", "must not exceed hard ceiling " + HardLimits.MAX_LOG_BYTES + " bytes");
        }
        if (scriptMaxBytes < 1 || scriptMaxBytes > HardLimits.MAX_SCRIPT_BYTES) {
            violations.put("scriptMaxBytes", "must not exceed hard ceiling " + HardLimits.MAX_SCRIPT_BYTES + " bytes");
        }
        if (artifactRetentionHours < 1 || artifactRetentionHours > HardLimits.MAX_ARTIFACT_RETENTION_HOURS) {
            violations.put(
                    "artifactRetentionHours",
                    "must not exceed hard ceiling " + HardLimits.MAX_ARTIFACT_RETENTION_HOURS + " hours");
        }
        if (perAlarmConcurrency < 1 || perAlarmConcurrency > HardLimits.MAX_PER_ALARM_CONCURRENCY) {
            violations.put(
                    "perAlarmConcurrency", "must not exceed hard ceiling " + HardLimits.MAX_PER_ALARM_CONCURRENCY);
        }
        if (globalConcurrency < 1 || globalConcurrency > HardLimits.MAX_GLOBAL_CONCURRENCY) {
            violations.put("globalConcurrency", "must not exceed hard ceiling " + HardLimits.MAX_GLOBAL_CONCURRENCY);
        }
        if (cpu == null || cpu.isBlank()) {
            violations.put("cpu", "must be a non-blank Kubernetes quantity");
        } else {
            validateQuantity(
                    violations,
                    "cpu",
                    cpu,
                    HardLimits.MAX_CPU_MILLICORES,
                    "millicores",
                    SandboxProperties::parseCpuMillicores);
            // Normalize to the canonical trimmed form so capabilities and downstream consumers
            // never emit a value that Kubernetes would reject for surrounding whitespace.
            cpu = cpu.trim();
        }
        if (memory == null || memory.isBlank()) {
            violations.put("memory", "must be a non-blank Kubernetes quantity");
        } else {
            validateQuantity(
                    violations,
                    "memory",
                    memory,
                    HardLimits.MAX_MEMORY_BYTES,
                    "bytes",
                    SandboxProperties::parseByteQuantity);
            memory = memory.trim();
        }
        if (ephemeralStorage == null || ephemeralStorage.isBlank()) {
            violations.put("ephemeralStorage", "must be a non-blank Kubernetes quantity");
        } else {
            validateQuantity(
                    violations,
                    "ephemeralStorage",
                    ephemeralStorage,
                    HardLimits.MAX_EPHEMERAL_STORAGE_BYTES,
                    "bytes",
                    SandboxProperties::parseByteQuantity);
            ephemeralStorage = ephemeralStorage.trim();
        }
        validateControllerCall(violations);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("invalid sandbox configuration: " + violations);
        }
    }

    private void validateQuantity(
            Map<String, String> violations,
            String field,
            String value,
            long ceiling,
            String unit,
            java.util.function.Function<String, Long> parser) {
        Long parsed;
        try {
            parsed = parser.apply(value);
        } catch (RuntimeException ex) {
            // IllegalArgumentException = malformed/non-kubernetes syntax; ArithmeticException =
            // a fractional quantity that does not map to a whole unit (e.g. 0.5001 cores).
            violations.put(field, "must be a valid Kubernetes quantity: " + value);
            return;
        }
        if (parsed == null || parsed <= 0) {
            violations.put(field, "must be a positive Kubernetes quantity: " + value);
        } else if (parsed > ceiling) {
            violations.put(field, "must not exceed hard ceiling " + ceiling + " " + unit + ": " + value);
        }
    }

    private void validateControllerCall(Map<String, String> violations) {
        if (enabled) {
            if (controllerEndpoint == null || controllerEndpoint.isBlank()) {
                violations.put("controllerEndpoint", "is required when sandbox is enabled");
            }
            if (controllerKeyId == null || controllerKeyId.isBlank()) {
                violations.put("controllerKeyId", "is required when sandbox is enabled");
            }
            if (controllerHmacSecret == null || controllerHmacSecret.isBlank()) {
                violations.put("controllerHmacSecret", "is required when sandbox is enabled");
            }
        }
        if (controllerConnectTimeoutMillis < 1
                || controllerConnectTimeoutMillis > HardLimits.MAX_CONTROLLER_CONNECT_TIMEOUT_MILLIS) {
            violations.put(
                    "controllerConnectTimeoutMillis",
                    "must be between 1 and " + HardLimits.MAX_CONTROLLER_CONNECT_TIMEOUT_MILLIS + " ms");
        }
        if (controllerReadTimeoutMillis < 1
                || controllerReadTimeoutMillis > HardLimits.MAX_CONTROLLER_READ_TIMEOUT_MILLIS) {
            violations.put(
                    "controllerReadTimeoutMillis",
                    "must be between 1 and " + HardLimits.MAX_CONTROLLER_READ_TIMEOUT_MILLIS + " ms");
        }
        if (controllerMaxResponseBytes < 1 || controllerMaxResponseBytes > HardLimits.MAX_CONTROLLER_RESPONSE_BYTES) {
            violations.put(
                    "controllerMaxResponseBytes",
                    "must not exceed hard ceiling " + HardLimits.MAX_CONTROLLER_RESPONSE_BYTES + " bytes");
        }
    }

    /**
     * Kubernetes-quantity mantissa grammar: an optional sign, then digits with an optional single
     * decimal point. Deliberately stricter than {@link Double#parseDouble} so Java-only forms such as
     * {@code 0x1.0p0} or {@code 1f} — which Kubernetes itself rejects — fail loudly here.
     */
    private static final java.util.regex.Pattern QUANTITY_MANTISSA =
            java.util.regex.Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?|[+-]?\\.\\d+)$");

    /**
     * Parses a Kubernetes CPU quantity into millicores using exact {@link java.math.BigDecimal}
     * arithmetic, so a value a hair above one core cannot round down past the ceiling. Supports plain
     * cores (e.g. {@code "1"}, {@code "0.5"}) and millicores ({@code "100m"}); rejects negatives and
     * any other suffix, which is not a valid CPU unit.
     */
    private static Long parseCpuMillicores(String value) {
        String trimmed = value.trim();
        if (trimmed.endsWith("m")) {
            String mantissa = trimmed.substring(0, trimmed.length() - 1);
            // BigDecimal would accept exponents (e.g. "1e3m"); Kubernetes does not allow an exponent
            // alongside the millicores suffix, so enforce the Kubernetes-compatible grammar first.
            if (!QUANTITY_MANTISSA.matcher(mantissa).matches()) {
                throw new IllegalArgumentException("non-kubernetes cpu quantity: " + value);
            }
            java.math.BigDecimal millicores = new java.math.BigDecimal(mantissa);
            if (millicores.signum() < 0) {
                throw new IllegalArgumentException("negative cpu");
            }
            return millicores.setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
        }
        if (!QUANTITY_MANTISSA.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("non-kubernetes cpu quantity: " + value);
        }
        java.math.BigDecimal cores = new java.math.BigDecimal(trimmed);
        if (cores.signum() < 0) {
            throw new IllegalArgumentException("negative cpu");
        }
        return cores.multiply(java.math.BigDecimal.valueOf(1000L))
                .setScale(0, java.math.RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    /**
     * Parses a Kubernetes storage/byte quantity into bytes using exact {@link java.math.BigDecimal}
     * arithmetic, so a fractional value just over the ceiling cannot round down and slip through.
     * Supports binary (Ki/Mi/Gi/Ti) and decimal (k/K/M/G/T) suffixes as well as plain bytes; rejects
     * unknown suffixes, Java-only numeric forms and negatives so misconfiguration fails loudly rather
     * than being advertised as a valid limit.
     */
    private static Long parseByteQuantity(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))([A-Za-z]*)$")
                .matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unparseable quantity: " + value);
        }
        java.math.BigDecimal mantissa = new java.math.BigDecimal(matcher.group(1));
        if (mantissa.signum() < 0) {
            throw new IllegalArgumentException("negative quantity");
        }
        java.math.BigDecimal multiplier =
                switch (matcher.group(2)) {
                    case "" -> java.math.BigDecimal.ONE;
                    case "Ki" -> java.math.BigDecimal.valueOf(1L << 10);
                    case "Mi" -> java.math.BigDecimal.valueOf(1L << 20);
                    case "Gi" -> java.math.BigDecimal.valueOf(1L << 30);
                    case "Ti" -> java.math.BigDecimal.valueOf(1L << 40);
                    case "k", "K" -> java.math.BigDecimal.valueOf(1_000L);
                    case "M" -> java.math.BigDecimal.valueOf(1_000_000L);
                    case "G" -> java.math.BigDecimal.valueOf(1_000_000_000L);
                    case "T" -> java.math.BigDecimal.valueOf(1_000_000_000_000L);
                    default -> throw new IllegalArgumentException("unknown quantity suffix: " + matcher.group(2));
                };
        java.math.BigDecimal bytes = mantissa.multiply(multiplier);
        if (bytes.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("quantity overflows long: " + value);
        }
        return bytes.setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
    }

    /**
     * Returns a capability-safe snapshot of the enforced limits for the {@code /api/v1/capabilities}
     * surface. Only the immutable ceilings and validated operator values are exposed; the controller
     * endpoint, secrets and any pre-signed inputs are deliberately omitted.
     */
    public Map<String, Object> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeoutSeconds", timeoutSeconds);
        limits.put("maxTimeoutSeconds", maxTimeoutSeconds);
        limits.put("cpu", cpu);
        limits.put("memory", memory);
        limits.put("ephemeralStorage", ephemeralStorage);
        limits.put("inputMaxBytes", inputMaxBytes);
        limits.put("outputMaxBytes", outputMaxBytes);
        limits.put("logMaxBytes", logMaxBytes);
        limits.put("scriptMaxBytes", scriptMaxBytes);
        limits.put("artifactRetentionHours", artifactRetentionHours);
        limits.put("perAlarmConcurrency", perAlarmConcurrency);
        limits.put("globalConcurrency", globalConcurrency);
        return limits;
    }
}
