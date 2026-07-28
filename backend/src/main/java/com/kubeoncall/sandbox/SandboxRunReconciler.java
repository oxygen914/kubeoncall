package com.kubeoncall.sandbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.service.KubeOnCallMetricsService;

/**
 * Short, fenced convergence loop for Controller-owned Jobs.
 *
 * <p>Each iteration claims at most one Run and performs at most one status request plus an optional
 * bounded result collection. The claim is released for non-terminal Runs, so replicas cooperate via
 * the persisted fencing token instead of holding a Worker thread for the Job's lifetime.
 */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SandboxRunReconciler {

    private final SandboxRunRepository repository;
    private final SandboxControllerClient controller;
    private final SandboxArtifactStore artifactStore;
    private final KubeOnCallProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxWriter outboxWriter;
    private final KubeOnCallMetricsService metrics;
    private final String ownerToken = "sandbox-reconciler-" + UUID.randomUUID();

    @Autowired
    public SandboxRunReconciler(
            SandboxRunRepository repository,
            SandboxControllerClient controller,
            SandboxArtifactStore artifactStore,
            KubeOnCallProperties properties,
            OutboxWriter outboxWriter,
            KubeOnCallMetricsService metrics) {
        this(repository, controller, artifactStore, properties, outboxWriter, metrics, Clock.systemUTC());
    }

    SandboxRunReconciler(
            SandboxRunRepository repository,
            SandboxControllerClient controller,
            SandboxArtifactStore artifactStore,
            KubeOnCallProperties properties,
            OutboxWriter outboxWriter,
            KubeOnCallMetricsService metrics,
            Clock clock) {
        this.repository = repository;
        this.controller = controller;
        this.artifactStore = artifactStore;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.sandbox.reconcile-poll-millis:1000}")
    public void scheduledReconcile() {
        runOnce();
    }

    /** Deterministic single-item unit for scheduling and multi-instance tests. */
    public Outcome runOnce() {
        Instant now = clock.instant();
        SandboxRunRecord claimed = repository
                .claimForReconciliation(ownerToken, now, leaseDuration())
                .orElse(null);
        if (claimed == null) {
            Outcome cleanup = reconcileCleanupOnce(now);
            metrics.recordSandboxRunEvent("cleanup", "unknown", "unknown", cleanup.name());
            return cleanup;
        }
        Outcome outcome;
        try {
            outcome = reconcile(claimed, now);
        } catch (SandboxControllerClientException unavailable) {
            // A controller outage must leave the Run recoverable; no terminal business fact is
            // inferred from a transport failure. The following poll will claim it again.
            outcome = Outcome.DEFERRED;
        } finally {
            repository.releaseReconciliationClaim(claimed.publicId(), ownerToken, claimed.fencingToken());
        }
        recordOutcome(claimed, outcome, now);
        return outcome;
    }

    private void recordOutcome(SandboxRunRecord run, Outcome outcome, Instant now) {
        String event =
                switch (outcome) {
                    case RUNNING -> "running";
                    case SUCCEEDED -> "completed";
                    case FAILED -> "failed";
                    case TIMED_OUT -> "timed_out";
                    case CANCELLED -> "cancelled";
                    case FENCE_REJECTED -> "fence_rejected";
                    case DEFERRED -> "deferred";
                    default -> "reconciled";
                };
        metrics.recordSandboxRunEvent(event, run.mode().name(), run.toolId(), outcome.name());
        if (outcome == Outcome.SUCCEEDED
                || outcome == Outcome.FAILED
                || outcome == Outcome.TIMED_OUT
                || outcome == Outcome.CANCELLED) {
            Instant started = run.startedAt() == null ? run.createdAt() : run.startedAt();
            if (started != null) {
                metrics.recordSandboxRunDuration(
                        run.mode().name(),
                        run.toolId(),
                        outcome.name(),
                        Duration.between(started, now).toMillis());
            }
        }
    }

    private Outcome reconcile(SandboxRunRecord claimed, Instant now) {
        if (claimed.expiresAt() != null && !claimed.expiresAt().isAfter(now)) {
            // Timeout wins a late Controller success. Mark first under the fence, then issue a
            // best-effort cancel; a controller outage cannot resurrect this terminal Run.
            boolean timedOut = repository.fail(
                    claimed.publicId(),
                    ownerToken,
                    claimed.fencingToken(),
                    claimed.version(),
                    SandboxRunStatus.TIMED_OUT,
                    "SANDBOX_RUN_EXPIRED",
                    "Sandbox run reached its expiry deadline",
                    now);
            if (timedOut) {
                markCleanupPending(claimed.publicId(), now);
                controller.cancel(claimed);
                return Outcome.TIMED_OUT;
            }
            return Outcome.FENCE_REJECTED;
        }
        if (claimed.runStatus() == SandboxRunStatus.PENDING) {
            // SBX-12 owns dispatch. Leaving PENDING untouched prevents the reconciler from
            // creating a Job before the durable dispatch task has been claimed.
            return Outcome.PENDING_DISPATCH;
        }
        SandboxControllerClient.ControllerStatus status = controller.status(claimed);
        return switch (status.phase()) {
            case "PENDING" -> Outcome.PENDING;
            case "RUNNING" -> transitionRunning(claimed, now);
            case "SUCCEEDED" -> collectSucceeded(claimed, now);
            case "FAILED" -> fail(claimed, SandboxRunStatus.FAILED, failureCode(status), now);
            case "TIMED_OUT" -> fail(claimed, SandboxRunStatus.TIMED_OUT, "DEADLINE", now);
            case "CANCELLED" -> cancel(claimed, now);
            case "UNKNOWN" -> Outcome.DEFERRED;
            default -> Outcome.DEFERRED;
        };
    }

    private Outcome transitionRunning(SandboxRunRecord run, Instant now) {
        if (run.runStatus() == SandboxRunStatus.RUNNING) {
            return Outcome.RUNNING;
        }
        return repository.transitionRunStatus(
                        run.publicId(), ownerToken, run.fencingToken(), run.version(), SandboxRunStatus.RUNNING, now)
                ? Outcome.RUNNING
                : Outcome.FENCE_REJECTED;
    }

    private Outcome collectSucceeded(SandboxRunRecord claimed, Instant now) {
        SandboxRunRecord collecting = claimed;
        if (collecting.runStatus() == SandboxRunStatus.DISPATCHING) {
            if (!repository.transitionRunStatus(
                    collecting.publicId(),
                    ownerToken,
                    collecting.fencingToken(),
                    collecting.version(),
                    SandboxRunStatus.RUNNING,
                    now)) {
                return Outcome.FENCE_REJECTED;
            }
            collecting = repository.findByPublicId(collecting.publicId()).orElseThrow();
        }
        if (collecting.runStatus() == SandboxRunStatus.RUNNING) {
            if (!repository.transitionRunStatus(
                    collecting.publicId(),
                    ownerToken,
                    collecting.fencingToken(),
                    collecting.version(),
                    SandboxRunStatus.COLLECTING,
                    now)) {
                return Outcome.FENCE_REJECTED;
            }
            collecting = repository.findByPublicId(collecting.publicId()).orElseThrow();
        }
        if (collecting.runStatus() != SandboxRunStatus.COLLECTING) {
            return Outcome.FENCE_REJECTED;
        }
        SandboxControllerClient.CollectedResult result = controller.collect(collecting);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("controllerPhase", result.phase());
        summary.put("exitCode", result.exitCode());
        summary.put("reason", result.reason());
        summary.put("outputFound", result.outputFound());
        if (collecting.mode() == SandboxRunMode.GENERATED_CODE) {
            if (!result.outputFound() || !GeneratedCodeResultValidator.isValid(result.output(), objectMapper)) {
                return fail(collecting, SandboxRunStatus.FAILED, "GENERATED_OUTPUT_CONTRACT_INVALID", now);
            }
            SandboxArtifactStore.StoredArtifact output = artifactStore.store(
                    collecting.publicId(),
                    SandboxArtifactType.OUTPUT,
                    "result.json",
                    "application/json",
                    result.output().getBytes(StandardCharsets.UTF_8),
                    SandboxClassification.UNTRUSTED);
            repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                    null,
                    collecting.id(),
                    SandboxArtifactType.OUTPUT,
                    output.bucket(),
                    output.objectKey(),
                    output.contentType(),
                    output.sizeBytes(),
                    output.sha256(),
                    SandboxClassification.UNTRUSTED,
                    now.plus(Duration.ofHours(properties.getSandbox().getArtifactRetentionHours()))));
            summary.put("outputArtifactSha256", output.sha256());
        }
        if (collecting.mode() == SandboxRunMode.MANIFEST_VALIDATION) {
            if (!result.outputFound() || !ManifestValidationResultValidator.isValid(result.output(), objectMapper)) {
                return fail(collecting, SandboxRunStatus.FAILED, "MANIFEST_OUTPUT_CONTRACT_INVALID", now);
            }
            SandboxArtifactStore.StoredArtifact output = artifactStore.store(
                    collecting.publicId(),
                    SandboxArtifactType.OUTPUT,
                    "validation-result.json",
                    "application/json",
                    result.output().getBytes(StandardCharsets.UTF_8),
                    SandboxClassification.UNTRUSTED);
            repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                    null,
                    collecting.id(),
                    SandboxArtifactType.OUTPUT,
                    output.bucket(),
                    output.objectKey(),
                    output.contentType(),
                    output.sizeBytes(),
                    output.sha256(),
                    SandboxClassification.UNTRUSTED,
                    now.plus(Duration.ofHours(properties.getSandbox().getArtifactRetentionHours()))));
            summary.put("outputArtifactSha256", output.sha256());
        }
        if (collecting.mode() == SandboxRunMode.REMEDIATION_SIMULATION) {
            if (!result.outputFound() || !RemediationSimulationResultValidator.isValid(result.output(), objectMapper)) {
                return fail(collecting, SandboxRunStatus.FAILED, "SIMULATION_OUTPUT_CONTRACT_INVALID", now);
            }
            // The validation-cluster runtime can only return an untrusted rehearsal observation.
            // SBX-20 converts it into a proposal and independently re-checks production state.
            SandboxArtifactStore.StoredArtifact output = artifactStore.store(
                    collecting.publicId(),
                    SandboxArtifactType.OUTPUT,
                    "remediation-simulation-result.json",
                    "application/json",
                    result.output().getBytes(StandardCharsets.UTF_8),
                    SandboxClassification.UNTRUSTED);
            repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                    null,
                    collecting.id(),
                    SandboxArtifactType.OUTPUT,
                    output.bucket(),
                    output.objectKey(),
                    output.contentType(),
                    output.sizeBytes(),
                    output.sha256(),
                    SandboxClassification.UNTRUSTED,
                    now.plus(Duration.ofHours(properties.getSandbox().getArtifactRetentionHours()))));
            summary.put("outputArtifactSha256", output.sha256());
            summary.put("simulationOnly", true);
        }
        if (!result.logs().isBlank()) {
            SandboxArtifactStore.StoredArtifact artifact = artifactStore.store(
                    collecting.publicId(),
                    SandboxArtifactType.LOG,
                    "controller.log",
                    "text/plain; charset=utf-8",
                    result.logs().getBytes(StandardCharsets.UTF_8),
                    SandboxClassification.UNTRUSTED);
            repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                    null,
                    collecting.id(),
                    SandboxArtifactType.LOG,
                    artifact.bucket(),
                    artifact.objectKey(),
                    artifact.contentType(),
                    artifact.sizeBytes(),
                    artifact.sha256(),
                    SandboxClassification.UNTRUSTED,
                    now.plus(Duration.ofHours(properties.getSandbox().getArtifactRetentionHours()))));
            summary.put("logArtifactSha256", artifact.sha256());
        }
        boolean completed = repository.complete(
                collecting.publicId(), ownerToken, collecting.fencingToken(), collecting.version(), summary, now);
        if (completed) {
            markCleanupPending(collecting.publicId(), now);
        }
        return completed ? Outcome.SUCCEEDED : Outcome.FENCE_REJECTED;
    }

    private Outcome fail(SandboxRunRecord run, SandboxRunStatus terminal, String code, Instant now) {
        boolean failed = repository.fail(
                run.publicId(),
                ownerToken,
                run.fencingToken(),
                run.version(),
                terminal,
                code,
                "Sandbox Controller reported " + terminal.name(),
                now);
        if (failed) {
            markCleanupPending(run.publicId(), now);
            metrics.recordSandboxRunEvent("failure_reason", run.mode().name(), run.toolId(), code);
        }
        return failed ? Outcome.FAILED : Outcome.FENCE_REJECTED;
    }

    private static String failureCode(SandboxControllerClient.ControllerStatus status) {
        for (String reason : status.failureReasons()) {
            String normalized = reason == null ? "" : reason.trim().toUpperCase(java.util.Locale.ROOT);
            if (normalized.contains("OOM")) return "OOM";
            if (normalized.contains("IMAGEPULL") || normalized.contains("IMAGE_PULL")) return "IMAGE_PULL";
            if (normalized.contains("POLICY")) return "POLICY_DENIED";
            if (normalized.contains("DEADLINE")) return "DEADLINE";
        }
        return "TOOL_FAILURE";
    }

    private Outcome cancel(SandboxRunRecord run, Instant now) {
        boolean cancelled = repository.transitionRunStatus(
                run.publicId(), ownerToken, run.fencingToken(), run.version(), SandboxRunStatus.CANCELLED, now);
        if (cancelled) {
            markCleanupPending(run.publicId(), now);
        }
        return cancelled ? Outcome.CANCELLED : Outcome.FENCE_REJECTED;
    }

    private void markCleanupPending(String runId, Instant now) {
        SandboxRunRecord terminal = repository.findByPublicId(runId).orElse(null);
        if (terminal != null && terminal.cleanupStatus() == SandboxCleanupStatus.NOT_REQUIRED) {
            if (repository.transitionCleanupStatus(runId, terminal.version(), SandboxCleanupStatus.PENDING, now)) {
                outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                        "sandbox-run",
                        terminal.publicId(),
                        "sandbox.run.terminal",
                        Map.of(
                                "runId", terminal.publicId(),
                                "executionId", terminal.executionPublicId() == null ? "" : terminal.executionPublicId(),
                                "status", terminal.runStatus().name(),
                                "mode", terminal.mode().name()),
                        terminal.requestId()));
            }
        }
    }

    private Outcome reconcileCleanupOnce(Instant now) {
        SandboxRunRecord claimed = repository
                .claimCleanupForReconciliation(ownerToken, now, leaseDuration())
                .orElse(null);
        if (claimed == null) {
            return Outcome.IDLE;
        }
        try {
            SandboxRunRecord current = claimed;
            if (current.cleanupStatus() == SandboxCleanupStatus.PENDING) {
                if (!repository.transitionCleanupStatus(
                        current.publicId(), current.version(), SandboxCleanupStatus.RUNNING, now)) {
                    return Outcome.FENCE_REJECTED;
                }
                current = repository.findByPublicId(current.publicId()).orElseThrow();
            }
            SandboxControllerClient.ControllerStatus status = controller.status(current);
            if (!status.exists()) {
                return repository.transitionCleanupStatus(
                                current.publicId(), current.version(), SandboxCleanupStatus.SUCCEEDED, now)
                        ? Outcome.CLEANED
                        : Outcome.FENCE_REJECTED;
            }
            controller.cancel(current);
            return Outcome.DEFERRED;
        } catch (SandboxControllerClientException unavailable) {
            return Outcome.DEFERRED;
        } finally {
            repository.releaseCleanupClaim(claimed.publicId(), ownerToken, claimed.fencingToken());
        }
    }

    private Duration leaseDuration() {
        long readMillis = Math.max(1, properties.getSandbox().getControllerReadTimeoutMillis());
        return Duration.ofSeconds(Math.max(30, (readMillis + 999) / 1000 + 10));
    }

    public enum Outcome {
        IDLE,
        PENDING_DISPATCH,
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        CANCELLED,
        CLEANED,
        DEFERRED,
        FENCE_REJECTED
    }
}
