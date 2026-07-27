package com.kubeoncall.agent.sandbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.sandbox.DiagnosticEvidenceArtifactService;
import com.kubeoncall.sandbox.DiagnosticEvidenceBuilder;
import com.kubeoncall.sandbox.SandboxRunCommandService;
import com.kubeoncall.sandbox.SandboxRunRecord;
import com.kubeoncall.sandbox.SandboxRunRepository;
import com.kubeoncall.sandbox.domain.SandboxRunMode;

/** Creates an Agent-routed fixed diagnostic Run with its required input Artifact in one transaction. */
@Service
public class SandboxAgentRunSubmissionService {

    private final SandboxRunCommandService commandService;
    private final SandboxRunRepository repository;
    private final DiagnosticEvidenceArtifactService evidenceArtifacts;

    public SandboxAgentRunSubmissionService(
            SandboxRunCommandService commandService,
            SandboxRunRepository repository,
            DiagnosticEvidenceArtifactService evidenceArtifacts) {
        this.commandService = commandService;
        this.repository = repository;
        this.evidenceArtifacts = evidenceArtifacts;
    }

    @Transactional
    public Submission submit(GraphState state, Task task, SandboxRoutingPolicy.Decision route) {
        if (route == null || !route.routed() || route.mode() != SandboxRunMode.FIXED_DIAGNOSTIC) {
            return Submission.notSubmitted("route is not a supported automatic fixed diagnostic");
        }
        if (state == null || task == null || blank(state.getExecutionId())) {
            throw new IllegalArgumentException("sandbox routing requires a durable execution and task");
        }
        Map<String, Object> actor = actor(state);
        String key = "agent-sandbox-" + state.getExecutionId() + "-" + task.taskId();
        SandboxRunCommandService.CommandResult result = commandService.create(
                new SandboxRunCommandService.CreateCommand(
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        "log-pattern-analysis",
                        "v1",
                        state.getExecutionId(),
                        text(state.getContext().get("alarmId")),
                        false,
                        false,
                        Instant.now().plusSeconds(300),
                        number(actor.get("userId")),
                        required(actor, "publicId"),
                        text(actor.get("displayName")),
                        "agent-sandbox-" + state.getExecutionId(),
                        null,
                        null,
                        null),
                new IdempotencyService.IdempotencyScope("USER", required(actor, "publicId"), "AGENT_SANDBOX_ROUTE"),
                key);
        String runId = String.valueOf(result.data().get("id"));
        SandboxRunRecord run = repository.findByPublicId(runId).orElseThrow();
        evidenceArtifacts.store(
                run,
                new DiagnosticEvidenceBuilder.BuildRequest(
                        "agent-sandbox-" + state.getExecutionId(),
                        null,
                        null,
                        Instant.now(),
                        List.of(new DiagnosticEvidenceBuilder.EvidenceItem(
                                DiagnosticEvidenceBuilder.EvidenceType.LOG,
                                "agent-graph-state",
                                String.join("\n", state.getObservations())))));
        return Submission.submitted(run.publicId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> actor(GraphState state) {
        Object value = state.getContext().get("workflowActor");
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("agent sandbox route is missing its durable workflow actor");
        }
        return (Map<String, Object>) raw;
    }

    private static long number(Object value) {
        if (value instanceof Number number && number.longValue() > 0) return number.longValue();
        throw new IllegalStateException("agent sandbox route actor userId is invalid");
    }

    private static String required(Map<String, Object> actor, String field) {
        String value = text(actor.get(field));
        if (blank(value)) throw new IllegalStateException("agent sandbox route actor " + field + " is missing");
        return value;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Submission(String runId, String reason) {
        static Submission submitted(String runId) {
            return new Submission(runId, "submitted");
        }

        static Submission notSubmitted(String reason) {
            return new Submission(null, reason);
        }

        public boolean submitted() {
            return runId != null;
        }
    }
}
