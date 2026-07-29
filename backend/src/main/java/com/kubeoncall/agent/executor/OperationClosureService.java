package com.kubeoncall.agent.executor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;

/**
 * Closes mutating operations with a fail-closed pre-snapshot, bounded health verification,
 * capability-aware rollback and best-effort human escalation.
 */
@Service
public class OperationClosureService {

    static final String CONTEXT_KEY = "operationClosure";
    private static final String KUBERNETES = "kubernetes";
    private static final List<String> MUTATING_KUBERNETES_ACTIONS =
            List.of("rolloutRestart", "scaleWorkload", "patchConfig");

    private final Map<String, ToolExecutor> executorsByKind;
    private final KubeOnCallProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public OperationClosureService(List<ToolExecutor> executors, KubeOnCallProperties properties) {
        this(executors, properties, Clock.systemUTC(), Thread::sleep);
    }

    OperationClosureService(
            List<ToolExecutor> executors, KubeOnCallProperties properties, Clock clock, Sleeper sleeper) {
        this.executorsByKind = executors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    Preparation prepare(GraphState state, ExecutionPlan plan, ToolDefinition definition) {
        if (definition == null || definition.readOnly()) {
            return Preparation.notRequired();
        }
        if (!properties.getAiOperations().isOperationClosureEnabled()) {
            return Preparation.blocked("Operation closure is disabled for a mutating operation");
        }
        if (!properties.getAgent().isPostExecutionVerificationEnabled()) {
            return Preparation.blocked("Post-execution verification is disabled for a mutating operation");
        }
        if (!KUBERNETES.equals(plan.executorKind()) || !MUTATING_KUBERNETES_ACTIONS.contains(plan.action())) {
            return Preparation.blocked("No verified recovery/rollback policy is registered for "
                    + plan.executorKind()
                    + "."
                    + plan.action());
        }

        ToolExecutor kubernetes = executorsByKind.get(KUBERNETES);
        if (kubernetes == null) {
            return Preparation.blocked("Kubernetes verifier is unavailable");
        }
        Map<String, Object> snapshotParameters = verificationParameters(state, plan, "PRE_EXECUTION", Map.of());
        Map<String, Object> snapshot = kubernetes.execute("describeWorkload", snapshotParameters);
        if (!successful(snapshot) || response(snapshot).isEmpty()) {
            return Preparation.blocked("Pre-execution workload snapshot is unavailable");
        }
        Map<String, Object> rollbackPolicy = rollbackPolicy(plan.action(), snapshot, plan.parameters());
        if (!Boolean.TRUE.equals(rollbackPolicy.get("available"))) {
            return Preparation.blocked(
                    String.valueOf(rollbackPolicy.getOrDefault("reason", "A safe rollback snapshot is unavailable")));
        }

        Map<String, Object> closure = new LinkedHashMap<>();
        closure.put("required", true);
        closure.put("status", "PREPARED");
        closure.put("action", plan.action());
        closure.put("target", target(state));
        closure.put("preparedAt", clock.instant().toString());
        closure.put("timeoutSeconds", timeout().toSeconds());
        closure.put("pollIntervalMillis", pollInterval().toMillis());
        closure.put("preSnapshot", snapshot);
        closure.put("rollbackPolicy", rollbackPolicy);
        state.getContext().put(CONTEXT_KEY, closure);
        return Preparation.ready(closure);
    }

    Outcome close(GraphState state) {
        ExecutionPlan plan = state.getContext().get("executionPlan") instanceof ExecutionPlan value ? value : null;
        ToolDefinition definition =
                state.getContext().get("executorToolDefinition") instanceof ToolDefinition value ? value : null;
        if (plan == null || definition == null || definition.readOnly()) {
            return Outcome.notRequired();
        }
        Map<String, Object> closure = mutableMap(state.getContext().get(CONTEXT_KEY));
        if (!Boolean.TRUE.equals(closure.get("required"))) {
            return Outcome.failure("BLOCKED", "Operation closure was not prepared", closure);
        }

        ToolExecutor executor = executorsByKind.get(plan.executorKind());
        if (executor == null) {
            return failAndEscalate(
                    state, plan, closure, "VERIFIER_UNAVAILABLE", "Post-execution verifier is unavailable");
        }

        Instant deadline = clock.instant().plus(timeout());
        List<Map<String, Object>> attempts = new ArrayList<>();
        Verification verification;
        do {
            Map<String, Object> result = executor.execute(
                    "describeWorkload", verificationParameters(state, plan, "POST_EXECUTION", expected(plan)));
            verification = evaluate(plan, result, expected(plan));
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("at", clock.instant().toString());
            attempt.put("status", verification.status());
            attempt.put("reason", verification.reason());
            attempt.put("result", result);
            attempts.add(attempt);
            if ("HEALTHY".equals(verification.status())) {
                closure.put("status", "VERIFIED");
                closure.put("verifiedAt", clock.instant().toString());
                closure.put("verificationAttempts", attempts);
                state.getContext().put(CONTEXT_KEY, closure);
                return Outcome.success(closure);
            }
            if ("FAILED".equals(verification.status())) {
                break;
            }
            if (!clock.instant().isBefore(deadline)) {
                verification = new Verification("TIMEOUT", "Recovery verification timed out");
                break;
            }
            try {
                sleeper.sleep(pollInterval().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                verification = new Verification("INTERRUPTED", "Recovery verification was interrupted");
                break;
            }
        } while (true);

        closure.put("verificationAttempts", attempts);
        closure.put("verificationFailure", verification.reason());
        Rollback rollback = rollback(state, plan, closure, executor);
        closure.put("rollback", rollback.details());
        String terminalStatus = rollback.succeeded() ? "ROLLED_BACK" : "ESCALATED";
        String message = rollback.succeeded()
                ? "Post-execution verification failed; the operation was rolled back"
                : "Post-execution verification failed and automatic recovery did not complete";
        return failAndEscalate(state, plan, closure, terminalStatus, message);
    }

    private Rollback rollback(
            GraphState state, ExecutionPlan plan, Map<String, Object> closure, ToolExecutor executor) {
        Map<String, Object> policy = mutableMap(closure.get("rollbackPolicy"));
        if (!properties.getAgent().isAutomaticRollbackEnabled()) {
            return Rollback.unavailable("Automatic rollback is disabled");
        }
        if (!Boolean.TRUE.equals(policy.get("available"))) {
            return Rollback.unavailable(String.valueOf(policy.getOrDefault("reason", "Rollback is unavailable")));
        }
        String action = String.valueOf(policy.get("action"));
        Map<String, Object> parameters = mutableMap(policy.get("parameters"));
        parameters.put("rollback", true);
        parameters.put("rollbackOfExecution", safe(state.getExecutionId()));
        parameters.put("operationId", rollbackOperationId(state, action));
        Map<String, Object> result = executor.execute(action, parameters);
        if (!successful(result)) {
            return Rollback.failed(action, parameters, result, "Rollback tool call failed");
        }

        Map<String, Object> verifyResult = executor.execute(
                "describeWorkload",
                verificationParameters(state, plan, "POST_ROLLBACK", mutableMap(policy.get("expected"))));
        Verification verification = evaluateRollback(verifyResult, mutableMap(policy.get("expected")));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("available", true);
        details.put("action", action);
        details.put("parameters", parameters);
        details.put("result", result);
        details.put("verification", verifyResult);
        details.put("status", verification.status());
        details.put("reason", verification.reason());
        return new Rollback("HEALTHY".equals(verification.status()), details);
    }

    private Outcome failAndEscalate(
            GraphState state, ExecutionPlan plan, Map<String, Object> closure, String status, String message) {
        closure.put("status", status);
        closure.put("finishedAt", clock.instant().toString());
        closure.put("escalation", escalate(state, plan, status, message));
        state.getContext().put(CONTEXT_KEY, closure);
        return Outcome.failure(status, message, closure);
    }

    private Map<String, Object> escalate(GraphState state, ExecutionPlan plan, String status, String message) {
        if (!properties.getAgent().isPostExecutionEscalationEnabled()) {
            return Map.of("status", "DISABLED");
        }
        ToolExecutor incident = executorsByKind.get("incident");
        if (incident == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "Incident executor is unavailable");
        }
        Task task = state.getCurrentTask();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("fingerprint", "execution:" + safe(state.getExecutionId()));
        parameters.put("severity", severity(task));
        parameters.put("summary", message);
        parameters.put("executionId", safe(state.getExecutionId()));
        parameters.put("target", target(state));
        parameters.put("failedAction", plan.executorKind() + "." + plan.action());
        parameters.put("closureStatus", status);
        Map<String, Object> result = incident.execute("escalateIncident", parameters);
        return Map.of("status", successful(result) ? "ESCALATED" : "ESCALATION_FAILED", "result", result);
    }

    private Map<String, Object> rollbackPolicy(
            String action, Map<String, Object> snapshot, Map<String, Object> originalParameters) {
        Map<String, Object> before = response(snapshot);
        Map<String, Object> parameters = new LinkedHashMap<>();
        copyIfPresent(originalParameters, parameters, "namespace");
        copyIfPresent(originalParameters, parameters, "target");
        return switch (action) {
            case "scaleWorkload" -> {
                Object replicas = firstPresent(before, "desiredReplicas", "replicas");
                if (replicas == null) {
                    yield unavailableRollback("Pre-execution replica count is missing");
                }
                parameters.put("replicas", replicas);
                yield availableRollback("scaleWorkload", parameters, Map.of("replicas", replicas));
            }
            case "patchConfig" -> {
                String key = String.valueOf(originalParameters.getOrDefault("configKey", ""));
                Object previous = previousConfigValue(before, key);
                if (key.isBlank() || previous == null) {
                    yield unavailableRollback("Pre-execution configuration value is missing");
                }
                parameters.put("configKey", key);
                parameters.put("desiredValue", previous);
                yield availableRollback("patchConfig", parameters, Map.of("configKey", key, "configValue", previous));
            }
            case "rolloutRestart" -> {
                Object revision = firstPresent(before, "revision", "currentRevision");
                if (revision == null) {
                    yield unavailableRollback("Pre-execution workload revision is missing");
                }
                parameters.put("revision", revision);
                yield availableRollback("rolloutUndo", parameters, Map.of("revision", revision));
            }
            default -> unavailableRollback("No rollback policy is registered");
        };
    }

    private Verification evaluate(ExecutionPlan plan, Map<String, Object> result, Map<String, Object> expected) {
        if (!successful(result)) {
            return new Verification("PENDING", "Verification endpoint is temporarily unavailable");
        }
        Map<String, Object> body = response(result);
        Verification explicit = explicitVerification(body);
        if (explicit != null) {
            return explicit;
        }
        return switch (plan.action()) {
            case "scaleWorkload" -> compareReplicas(body, expected.get("replicas"));
            case "patchConfig" ->
                compareConfig(body, String.valueOf(expected.get("configKey")), expected.get("configValue"));
            case "rolloutRestart" -> workloadHealthy(body);
            default -> new Verification("FAILED", "No post-execution verification policy is registered");
        };
    }

    private Verification evaluateRollback(Map<String, Object> result, Map<String, Object> expected) {
        if (!successful(result)) {
            return new Verification("FAILED", "Rollback verification endpoint failed");
        }
        Map<String, Object> body = response(result);
        Verification explicit = explicitVerification(body);
        if (explicit != null) {
            return explicit;
        }
        if (expected.containsKey("replicas")) {
            return compareReplicas(body, expected.get("replicas"));
        }
        if (expected.containsKey("configKey")) {
            return compareConfig(body, String.valueOf(expected.get("configKey")), expected.get("configValue"));
        }
        if (expected.containsKey("revision")) {
            Object actual = firstPresent(body, "revision", "currentRevision");
            return valuesEqual(actual, expected.get("revision"))
                    ? new Verification("HEALTHY", "Previous workload revision was restored")
                    : new Verification("FAILED", "Workload revision was not restored");
        }
        return new Verification("FAILED", "Rollback expectation is missing");
    }

    private Verification explicitVerification(Map<String, Object> body) {
        Object raw = firstPresent(body, "verificationStatus", "healthStatus");
        if (raw == null && body.get("healthy") instanceof Boolean healthy) {
            return healthy
                    ? new Verification("HEALTHY", "Workload reported healthy")
                    : new Verification("FAILED", "Workload reported unhealthy");
        }
        if (raw == null) {
            return null;
        }
        String status = String.valueOf(raw).toUpperCase(Locale.ROOT);
        String reason = String.valueOf(body.getOrDefault("reason", "Workload verification status=" + status));
        return switch (status) {
            case "HEALTHY", "SUCCEEDED", "READY", "RECOVERED" -> new Verification("HEALTHY", reason);
            case "FAILED", "UNHEALTHY", "DEGRADED" -> new Verification("FAILED", reason);
            default -> new Verification("PENDING", reason);
        };
    }

    private Verification compareReplicas(Map<String, Object> body, Object expected) {
        Object desired = firstPresent(body, "desiredReplicas", "replicas");
        Object ready = firstPresent(body, "readyReplicas", "availableReplicas");
        if (valuesEqual(desired, expected) && valuesEqual(ready, expected)) {
            return new Verification("HEALTHY", "Desired and ready replica counts match");
        }
        return new Verification("PENDING", "Replica convergence is still pending");
    }

    private Verification compareConfig(Map<String, Object> body, String key, Object expected) {
        Object actual = previousConfigValue(body, key);
        if (valuesEqual(actual, expected)) {
            return new Verification("HEALTHY", "Configuration value matches the expected state");
        }
        return new Verification("PENDING", "Configuration propagation is still pending");
    }

    private Verification workloadHealthy(Map<String, Object> body) {
        Object desired = firstPresent(body, "desiredReplicas", "replicas");
        Object ready = firstPresent(body, "readyReplicas", "availableReplicas");
        if (desired != null && valuesEqual(desired, ready)) {
            return new Verification("HEALTHY", "Workload replicas are ready after rollout");
        }
        return new Verification("PENDING", "Workload rollout is still converging");
    }

    private Map<String, Object> expected(ExecutionPlan plan) {
        return switch (plan.action()) {
            case "scaleWorkload" -> Map.of("replicas", plan.parameters().get("replicas"));
            case "patchConfig" ->
                Map.of(
                        "configKey", plan.parameters().get("configKey"),
                        "configValue", plan.parameters().get("desiredValue"));
            case "rolloutRestart" -> Map.of("healthy", true);
            default -> Map.of();
        };
    }

    private Map<String, Object> verificationParameters(
            GraphState state, ExecutionPlan plan, String phase, Map<String, Object> expected) {
        Map<String, Object> parameters = new LinkedHashMap<>(plan.parameters());
        parameters.put("target", target(state));
        parameters.put("verificationPhase", phase);
        parameters.put("expectedState", expected);
        parameters.put("executionId", safe(state.getExecutionId()));
        return parameters;
    }

    private String target(GraphState state) {
        Task task = state.getCurrentTask();
        return task == null || task.target() == null ? "" : task.target();
    }

    private String severity(Task task) {
        RiskLevel risk = task == null ? null : task.riskLevel();
        return risk == RiskLevel.CRITICAL ? "P1" : risk == RiskLevel.HIGH ? "P2" : "P3";
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, properties.getAgent().getPostExecutionVerificationTimeoutSeconds()));
    }

    private Duration pollInterval() {
        return Duration.ofMillis(Math.max(10, properties.getAgent().getPostExecutionVerificationPollMillis()));
    }

    private static boolean successful(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object httpStatus = result.get("httpStatus");
        if (httpStatus instanceof Number number && number.intValue() >= 400) {
            return false;
        }
        return "success".equalsIgnoreCase(String.valueOf(result.getOrDefault("status", "failed")));
    }

    private static Map<String, Object> response(Map<String, Object> result) {
        return result == null ? Map.of() : mutableMap(result.get("response"));
    }

    private static Map<String, Object> mutableMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
        }
        return result;
    }

    private static Object previousConfigValue(Map<String, Object> body, String key) {
        Object configuration = body.get("configuration");
        if (configuration instanceof Map<?, ?> values && values.containsKey(key)) {
            return values.get(key);
        }
        return firstPresent(body, "configValue", "currentValue", "previousValue");
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
        }
        return left != null && right != null && String.valueOf(left).equals(String.valueOf(right));
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private static Map<String, Object> availableRollback(
            String action, Map<String, Object> parameters, Map<String, Object> expected) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("available", true);
        policy.put("action", action);
        policy.put("parameters", parameters);
        policy.put("expected", expected);
        return policy;
    }

    private static Map<String, Object> unavailableRollback(String reason) {
        return Map.of("available", false, "reason", reason);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String rollbackOperationId(GraphState state, String action) {
        String executionId = state.getExecutionId() == null ? "unassigned" : state.getExecutionId();
        String taskId =
                state.getCurrentTask() == null ? "task" : state.getCurrentTask().taskId();
        return executionId + ":" + taskId + ":rollback:" + action;
    }

    record Preparation(boolean required, boolean ready, String reason, Map<String, Object> details) {
        static Preparation notRequired() {
            return new Preparation(false, true, null, Map.of("required", false, "status", "NOT_REQUIRED"));
        }

        static Preparation ready(Map<String, Object> details) {
            return new Preparation(true, true, null, details);
        }

        static Preparation blocked(String reason) {
            return new Preparation(
                    true, false, reason, Map.of("required", true, "status", "BLOCKED", "reason", reason));
        }
    }

    record Outcome(boolean successful, String status, String message, Map<String, Object> details) {
        static Outcome notRequired() {
            return new Outcome(true, "NOT_REQUIRED", "Post-execution verification is not required", Map.of());
        }

        static Outcome success(Map<String, Object> details) {
            return new Outcome(true, "VERIFIED", "Post-execution recovery was verified", details);
        }

        static Outcome failure(String status, String message, Map<String, Object> details) {
            return new Outcome(false, status, message, details);
        }
    }

    private record Verification(String status, String reason) {}

    private record Rollback(boolean succeeded, Map<String, Object> details) {
        static Rollback unavailable(String reason) {
            return new Rollback(false, Map.of("available", false, "status", "UNAVAILABLE", "reason", reason));
        }

        static Rollback failed(
                String action, Map<String, Object> parameters, Map<String, Object> result, String reason) {
            return new Rollback(
                    false,
                    Map.of(
                            "available", true,
                            "status", "FAILED",
                            "action", action,
                            "parameters", parameters,
                            "result", result,
                            "reason", reason));
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
