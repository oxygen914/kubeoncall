package com.kubeoncall.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    void shouldVerifyRestartOnlyAfterOperationMarkerAndUpdatedReplicasConverge() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of("revision", "4", "desiredReplicas", 2, "readyReplicas", 2));
        kubernetes.postVerification = response(Map.of(
                "generation", 2,
                "observedGeneration", 2,
                "desiredReplicas", 2,
                "readyReplicas", 2,
                "updatedReplicas", 2,
                "healthy", true));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(restartPlan());

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.rolloutRestart"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertTrue(outcome.successful());
        assertEquals("VERIFIED", outcome.status());
    }

    @Test
    void shouldRollbackRestartWhenHealthyWorkloadLacksTheExpectedOperationMarker() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of("revision", "4", "desiredReplicas", 2, "readyReplicas", 2));
        kubernetes.postVerification = response(Map.of(
                "generation", 2,
                "observedGeneration", 2,
                "desiredReplicas", 2,
                "readyReplicas", 2,
                "updatedReplicas", 2,
                "healthy", true));
        kubernetes.includePostOperationMarker = false;
        kubernetes.rollbackVerification = response(Map.of("revision", "4"));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(restartPlan());

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.rolloutRestart"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertFalse(outcome.successful());
        assertEquals("ROLLED_BACK", outcome.status());
        assertTrue(kubernetes.rollbackCalled);
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
        assertEquals(3, kubernetes.rollbackParameters.get("expectedCurrentReplicas"));
        assertEquals("uid-1", kubernetes.rollbackParameters.get("expectedResourceUid"));
        assertEquals(
                "exec-closure:task-1:kubernetes.scaleWorkload",
                kubernetes.rollbackParameters.get("rollbackOfOperationId"));
        assertEquals("escalateIncident", incident.actions.get(0));
        assertEquals("ROLLED_BACK", closure(state).get("status"));
        assertEquals("HEALTHY", ((Map<?, ?>) closure(state).get("rollback")).get("status"));
    }

    @Test
    void shouldPollRollbackVerificationUntilTheWorkloadConverges() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.postVerification = response(Map.of("verificationStatus", "FAILED"));
        kubernetes.rollbackVerifications = List.of(
                response(Map.of("desiredReplicas", 3, "readyReplicas", 2)),
                response(Map.of("desiredReplicas", 2, "readyReplicas", 2)));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertFalse(outcome.successful());
        assertEquals("ROLLED_BACK", outcome.status());
        Map<?, ?> rollback = (Map<?, ?>) closure(state).get("rollback");
        assertEquals("HEALTHY", rollback.get("status"));
        assertEquals(2, ((List<?>) rollback.get("verificationAttempts")).size());
    }

    @Test
    void shouldNotAcceptExplicitHealthyStatusWithoutTheExpectedScaleState() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.postVerification = response(Map.of("verificationStatus", "HEALTHY"));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertFalse(outcome.successful());
        assertEquals("ROLLED_BACK", outcome.status());
        assertTrue(kubernetes.rollbackCalled);
    }

    @Test
    void shouldRetryAnAmbiguousRollbackWithTheSameOperationId() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.postVerification = response(Map.of("verificationStatus", "FAILED"));
        kubernetes.rollbackResults = List.of(
                Map.of("status", "failed", "httpStatus", 503, "errorType", "OPERATION_LEDGER_UNAVAILABLE"),
                response(Map.of("accepted", true)));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertFalse(outcome.successful());
        assertEquals("ROLLED_BACK", outcome.status());
        assertEquals(2, kubernetes.rollbackDispatchParameters.size());
        assertEquals(
                kubernetes.rollbackDispatchParameters.get(0).get("operationId"),
                kubernetes.rollbackDispatchParameters.get(1).get("operationId"));
        Map<?, ?> rollback = (Map<?, ?>) closure(state).get("rollback");
        assertEquals(2, ((List<?>) rollback.get("dispatchAttempts")).size());
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
    void shouldBlockMutationWhenPreExecutionIdentityIsIncomplete() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = Map.of(
                "status", "success", "httpStatus", 200, "response", Map.of("desiredReplicas", 2, "readyReplicas", 2));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        OperationClosureService.Preparation preparation =
                service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"));

        assertFalse(preparation.ready());
        assertEquals("Pre-execution workload identity is incomplete", preparation.reason());
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

    @Test
    void shouldBuildGuardedPatchConfigRollbackFromManagedSnapshot() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of(
                "configuration", Map.of("REQUEST_TIMEOUT", "30s"),
                "desiredReplicas", 2,
                "readyReplicas", 2));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        ExecutionPlan patchPlan = new ExecutionPlan(
                "kubernetes",
                "patchConfig",
                Map.of(
                        "namespace", "prod",
                        "configKey", "REQUEST_TIMEOUT",
                        "desiredValue", "60s"),
                List.of("namespace", "configKey", "desiredValue"),
                List.of(),
                Map.of(),
                "patch",
                null);
        GraphState state = state(patchPlan);

        OperationClosureService.Preparation preparation =
                service.prepare(state, patchPlan, mutatingTool("kubernetes.patchConfig"));

        assertTrue(preparation.ready());
        Map<?, ?> rollbackPolicy = (Map<?, ?>) preparation.details().get("rollbackPolicy");
        Map<?, ?> parameters = (Map<?, ?>) rollbackPolicy.get("parameters");
        assertEquals("patchConfig", rollbackPolicy.get("action"));
        assertEquals("30s", parameters.get("desiredValue"));
        assertEquals("60s", parameters.get("expectedCurrentValue"));
        assertEquals("uid-1", parameters.get("expectedResourceUid"));
    }

    @Test
    void shouldReusePreparedSnapshotWhenTheSameOperationIsRetried() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        OperationClosureService service = service(clock, kubernetes, new SuccessfulIncidentExecutor());
        GraphState state = state(scalePlan(3));

        OperationClosureService.Preparation first =
                service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"));
        kubernetes.preSnapshot = response(Map.of("desiredReplicas", 3, "readyReplicas", 3));
        OperationClosureService.Preparation retried =
                service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"));

        assertTrue(first.ready());
        assertTrue(retried.ready());
        assertEquals(first.details().get("preSnapshot"), retried.details().get("preSnapshot"));
        assertEquals(
                1,
                kubernetes.actions.stream().filter("describeWorkload"::equals).count());
    }

    @Test
    void shouldPersistPreparedVerifyingAndVerifiedClosureFacts() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.preSnapshot = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        kubernetes.postVerification = response(Map.of("desiredReplicas", 3, "readyReplicas", 3));
        OperationClosureFactRepository repository = mock(OperationClosureFactRepository.class);
        KubeOnCallProperties properties = testProperties();
        OperationClosureService service = new OperationClosureService(
                List.of(kubernetes, new SuccessfulIncidentExecutor()),
                properties,
                clock,
                millis -> clock.advance(Duration.ofMillis(millis)),
                repository,
                true);
        GraphState state = state(scalePlan(3));

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"))
                .ready());
        assertTrue(service.close(state).successful());

        ArgumentCaptor<String> phases = ArgumentCaptor.forClass(String.class);
        verify(repository, atLeast(3))
                .upsertClosure(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        phases.capture(),
                        anyMap(),
                        nullable(String.class),
                        any(Instant.class),
                        nullable(Instant.class));
        assertTrue(phases.getAllValues().containsAll(List.of("PREPARED", "VERIFYING", "VERIFIED")));
        assertEquals(
                "exec-closure:task-1:kubernetes.scaleWorkload", closure(state).get("operationId"));
    }

    @Test
    void shouldRequireHealthToRemainStableBeforeMarkingOperationVerified() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        KubeOnCallProperties properties = testProperties();
        properties.getAgent().setPostExecutionVerificationTimeoutSeconds(2);
        properties.getAgent().setPostExecutionVerificationStableWindowSeconds(1);
        OperationClosureService service = new OperationClosureService(
                List.of(kubernetes, new SuccessfulIncidentExecutor()),
                properties,
                clock,
                millis -> clock.advance(Duration.ofMillis(millis)));
        GraphState state = state(scalePlan(3));

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertTrue(outcome.successful());
        assertEquals("VERIFIED", outcome.status());
        assertEquals(1L, closure(state).get("stableForSeconds"));
        assertEquals(
                6,
                kubernetes.actions.stream().filter("describeWorkload"::equals).count());
    }

    @Test
    void shouldFailClosedBeforeMutationWhenClosureFactCannotBePersisted() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        OperationClosureFactRepository repository = mock(OperationClosureFactRepository.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository)
                .upsertClosure(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyMap(),
                        nullable(String.class),
                        any(Instant.class),
                        nullable(Instant.class));
        OperationClosureService service = new OperationClosureService(
                List.of(kubernetes),
                testProperties(),
                clock,
                millis -> clock.advance(Duration.ofMillis(millis)),
                repository,
                true);
        GraphState state = state(scalePlan(3));

        OperationClosureService.Preparation preparation =
                service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"));

        assertFalse(preparation.ready());
        assertEquals("Durable operation closure fact could not be persisted", preparation.reason());
        assertFalse(kubernetes.rollbackCalled);
    }

    @Test
    void shouldPersistManualEscalationFactWhenIncidentExecutorIsUnavailable() {
        MutableClock clock = new MutableClock();
        RecordingKubernetesExecutor kubernetes = new RecordingKubernetesExecutor();
        kubernetes.postVerification = response(Map.of("verificationStatus", "FAILED"));
        OperationClosureFactRepository repository = mock(OperationClosureFactRepository.class);
        OperationClosureService service = new OperationClosureService(
                List.of(kubernetes),
                testProperties(),
                clock,
                millis -> clock.advance(Duration.ofMillis(millis)),
                repository,
                true);
        GraphState state = state(scalePlan(3));

        assertTrue(service.prepare(state, plan(state), mutatingTool("kubernetes.scaleWorkload"))
                .ready());
        OperationClosureService.Outcome outcome = service.close(state);

        assertFalse(outcome.successful());
        verify(repository)
                .upsertEscalation(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq("PENDING_MANUAL"),
                        anyString(),
                        anyString(),
                        anyMap(),
                        nullable(String.class));
    }

    private static OperationClosureService service(MutableClock clock, ToolExecutor kubernetes, ToolExecutor incident) {
        KubeOnCallProperties properties = testProperties();
        return new OperationClosureService(
                List.of(kubernetes, incident), properties, clock, millis -> clock.advance(Duration.ofMillis(millis)));
    }

    private static KubeOnCallProperties testProperties() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAgent().setPostExecutionVerificationTimeoutSeconds(1);
        properties.getAgent().setPostExecutionVerificationPollMillis(250);
        properties.getAgent().setPostExecutionVerificationStableWindowSeconds(0);
        return properties;
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

    private static ExecutionPlan restartPlan() {
        return new ExecutionPlan(
                "kubernetes",
                "rolloutRestart",
                Map.of("namespace", "prod", "rolloutStrategy", "rolling"),
                List.of("namespace", "rolloutStrategy"),
                List.of(),
                Map.of(),
                "restart",
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
        Map<String, Object> enriched = new LinkedHashMap<>(body);
        enriched.putIfAbsent("resource", Map.of("kind", "Deployment", "name", "payment-service", "uid", "uid-1"));
        enriched.putIfAbsent("generation", 1L);
        return Map.of("status", "success", "httpStatus", 200, "response", enriched);
    }

    private static final class RecordingKubernetesExecutor implements ToolExecutor {

        private final List<String> actions = new ArrayList<>();
        private Map<String, Object> preSnapshot = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        private Map<String, Object> postVerification = response(Map.of("desiredReplicas", 3, "readyReplicas", 3));
        private Map<String, Object> rollbackVerification = response(Map.of("desiredReplicas", 2, "readyReplicas", 2));
        private List<Map<String, Object>> rollbackVerifications = List.of();
        private int rollbackVerificationIndex;
        private List<Map<String, Object>> rollbackResults = List.of();
        private int rollbackResultIndex;
        private boolean rollbackCalled;
        private boolean includePostOperationMarker = true;
        private Map<String, Object> rollbackParameters = Map.of();
        private final List<Map<String, Object>> rollbackDispatchParameters = new ArrayList<>();

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
            if (Boolean.TRUE.equals(parameters.get("rollback"))) {
                rollbackCalled = true;
                rollbackParameters = Map.copyOf(parameters);
                rollbackDispatchParameters.add(rollbackParameters);
                return nextRollbackResult();
            }
            if (!"describeWorkload".equals(action)) {
                return response(Map.of("accepted", true));
            }
            String phase = String.valueOf(parameters.get("verificationPhase"));
            Map<String, Object> result =
                    switch (phase) {
                        case "PRE_EXECUTION" -> preSnapshot;
                        case "POST_ROLLBACK" -> nextRollbackVerification();
                        default -> postVerification;
                    };
            if ("PRE_EXECUTION".equals(phase) || ("POST_EXECUTION".equals(phase) && !includePostOperationMarker)) {
                return result;
            }
            return withExpectedOperationMarker(result, parameters);
        }

        private Map<String, Object> nextRollbackVerification() {
            if (rollbackVerifications.isEmpty()) {
                return rollbackVerification;
            }
            int index = Math.min(rollbackVerificationIndex, rollbackVerifications.size() - 1);
            rollbackVerificationIndex++;
            return rollbackVerifications.get(index);
        }

        private Map<String, Object> nextRollbackResult() {
            if (rollbackResults.isEmpty()) {
                return response(Map.of("accepted", true));
            }
            int index = Math.min(rollbackResultIndex, rollbackResults.size() - 1);
            rollbackResultIndex++;
            return rollbackResults.get(index);
        }

        private static Map<String, Object> withExpectedOperationMarker(
                Map<String, Object> result, Map<String, Object> parameters) {
            if (!(result.get("response") instanceof Map<?, ?> rawResponse)
                    || !(parameters.get("expectedState") instanceof Map<?, ?> expected)
                    || expected.get("operationMarker") == null) {
                return result;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            rawResponse.forEach((key, value) -> body.put(String.valueOf(key), value));
            body.putIfAbsent("operationMarker", expected.get("operationMarker"));
            Map<String, Object> enriched = new LinkedHashMap<>(result);
            enriched.put("response", body);
            return enriched;
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
