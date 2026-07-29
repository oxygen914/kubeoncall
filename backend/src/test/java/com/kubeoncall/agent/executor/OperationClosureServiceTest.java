package com.kubeoncall.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;

class OperationClosureServiceTest {

    @Test
    void shouldVerifyScaleOperationAfterCapturingPreExecutionSnapshot() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        kubernetes.postVerification = response(Map.of("desiredReplicas", 3, "readyReplicas", 3));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        OperationClosureService.Preparation preparation =
                service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"));
        OperationClosureService.Outcome outcome = service.close(state);

        assertTrue(preparation.ready());
        assertTrue(outcome.successful());
        assertEquals("VERIFIED", outcome.status());
        assertEquals(List.of("describeWorkload", "describeWorkload"), kubernetes.actions);
        assertEquals("VERIFIED", closure(state).get("status"));
    }

    @Test
    void shouldRollbackAndEscalateWhenScaleRecoveryTimesOut() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        kubernetes.postVerification = response(Map.of("verificationStatus", "PENDING"));
        kubernetes.rollbackVerification = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        SuccessfulIncidentExecutor incident = new SuccessfulIncidentExecutor();
        OperationClosureService service = service(clock, kubernetes, incident);
        GraphState state = state(scalePlan(3));

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertFalse(outcome.successful());
        assertEquals("ROLLED_BACK", outcome.status());
        assertTrue(kubernetes.rollbackCalled);
        assertEquals(2, kubernetes.rollbackParameters.get("replicas"));
        assertEquals("escalateIncident", incident.actions.get(0));
        assertEquals("ROLLED_BACK", closure(state).get("status"));
        assertEquals("HEALTHY", ((Map<?, ?>) closure(state).get("rollback")).get("status"));
    }

    @Test
    void shouldBlockMutationWhenPreExecutionSnapshotIsUnavailable() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = Map.of("status", "failed", "httpStatus", 503);
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        OperationClosureService.Preparation preparation =
                service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"));

        assertFalse(preparation.ready());
        assertEquals("Pre-execution workload snapshot is unavailable", preparation.reason());
        assertFalse(kubernetes.rollbackCalled);
    }

    @Test
    void shouldBlockScaleMutationWhenSnapshotCannotBuildRollback() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of("verificationStatus", "HEALTHY"));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        OperationClosureService.Preparation preparation =
                service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"));

        assertFalse(preparation.ready());
        assertEquals("Pre-execution replica count is missing", preparation.reason());
    }

    @Test
    void shouldBlockMutationWithoutRegisteredClosurePolicy() {
        MutableClock clock = new MutableClock();
        OperationClosureService service =
                service(clock, new RecordingKubernetesExecutor(), new SuccessfulIncidentExecutor());
        GraphState state = state(new ExecutionPlan(
                "database",
                "cleanData",
                Map.of("namespace", "default", "target", "expired"),
                List.of(),
                List.of(),
                Map.of(),
                "clean",
                null));

        OperationClosureService.Preparation preparation =
                service.prepare(state, plan(state), mutatingTool("database.cleanData"));

        assertFalse(preparation.ready());
        assertTrue(preparation.reason().contains("No verified recovery/rollback policy"));
    }

    private static OperationClosureService service(MutableClock clock, ToolExecutor kubernetes, ToolExecutor incident) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAgent().setPostExecutionVerificationTimeoutSeconds(1);
        properties.getAgent().setPostExecutionVerificationPollMillis(250);
        return new OperationClosureService(
                List.of(kubernetes, incident), properties, clock, millis -> clock.advance(Duration.ofMillis(millis)));
    }

    private static GraphState state(ExecutionPlan plan) {
        GraphState state = new GraphState();
        state.setExecutionId("exec-closure");
        state.setCurrentTask(new Task(
                "task-1",
                "scale workload",
                TaskType.SCALE_WORKLOAD,
                RiskLevel.HIGH,
                "payment-service",
                plan.parameters(),
                new SopReference("SOP-1", "Scale", "1", "test")));
        state.getContext().put("executionPlan", plan);
        state.getContext().put("executorToolDefinition", mutatingTool("kubernetes." + plan.action()));
        return state;
    }

    private static ExecutionPlan scalePlan(int replicas) {
        return new ExecutionPlan(
                "kubernetes",
                "scaleWorkload",
                Map.of("namespace", "prod", "replicas", replicas),
                List.of("namespace", "replicas"),
                List.of(),
                Map.of(),
                "scale",
                null);
    }

    private static ExecutionPlan plan(GraphState state) {
        return (ExecutionPlan) state.getContext().get("executionPlan");
    }

    private static ToolDefinition mutatingTool(String name) {
        int separator = name.indexOf('.');
        return new ToolDefinition(
                name,
                separator < 0 ? "kubernetes" : name.substring(0, separator),
                "test",
                false,
                true,
                List.of(),
                List.of(),
                List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> closure(GraphState state) {
        return (Map<String, Object>) state.getContext().get(OperationClosureService.CONTEXT_KEY);
    }

    private static Map<String, Object> response(Map<String, Object> body) {
        return Map.of("status", "success", "httpStatus", 200, "response", body);
    }

    private static final class RecordingKubernetesExecutor implements ToolExecutor {

        private final List<String> actions = new ArrayList<>();
        private Map<String, Object> preSnapshot = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        private Map<String, Object> postVerification = response(Map.of("desiredReplicas", 3, "readyReplicas", 3));
        private Map<String, Object> rollbackVerification = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        private boolean rollbackCalled;
        private Map<String, Object> rollbackParameters = Map.of();

        @Override
        public String getExecutorKind() {
            return "kubernetes";
        }

        @Override
        public List<ToolDefinition> supportedTools() {
            return List.of();
        }

        @Override
        public Map<String, Object> execute(String action, Map<String, Object> parameters) {
            actions.add(action);
            if ("scaleWorkload".equals(action) && Boolean.TRUE.equals(parameters.get("rollback"))) {
                rollbackCalled = true;
                rollbackParameters = Map.copyOf(parameters);
                return response(Map.of("accepted", true));
            }
            if (!"describeWorkload".equals(action)) {
                return response(Map.of("accepted", true));
            }
            return switch (String.valueOf(parameters.get("verificationPhase"))) {
                case "PRE_EXECUTION" -> preSnapshot;
                case "POST_ROLLBACK" -> rollbackVerification;
                default -> postVerification;
            };
        }
    }

    private static final class SuccessfulIncidentExecutor implements ToolExecutor {

        private final List<String> actions = new ArrayList<>();

        @Override
        public String getExecutorKind() {
            return "incident";
        }

        @Override
        public List<ToolDefinition> supportedTools() {
            return List.of();
        }

        @Override
        public Map<String, Object> execute(String action, Map<String, Object> parameters) {
            actions.add(action);
            return response(Map.of("incidentId", "inc-1"));
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current = Instant.parse("2026-07-29T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
