package com.kubeoncall.sandbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;
import com.kubeoncall.task.worker.NonRetryableTaskException;

/**
 * Sends one idempotent create request to the Sandbox Controller then returns immediately.
 *
 * <p>It intentionally performs no Job polling or result collection: SBX-13 owns state convergence.
 * The initial Run claim protects the PENDING-to-DISPATCHING transition; subsequent task retries send
 * the same Run ID so a lost HTTP response cannot create a second Kubernetes Job.
 */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SandboxDispatchTaskHandler implements AsyncTaskHandler {

    public static final String TASK_TYPE = "SANDBOX_DISPATCH";

    private final SandboxRunRepository repository;
    private final SandboxControllerClient controllerClient;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metrics;
    private final Clock clock = Clock.systemUTC();

    public SandboxDispatchTaskHandler(
            SandboxRunRepository repository,
            SandboxControllerClient controllerClient,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metrics) {
        this.repository = repository;
        this.controllerClient = controllerClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        String runId = context.task().resourcePublicId();
        if (runId == null || runId.isBlank()) {
            throw new NonRetryableTaskException("SANDBOX_RUN_ID_MISSING");
        }
        context.requireValidLease();
        SandboxRunRecord run = repository
                .findByPublicId(runId)
                .orElseThrow(() -> new NonRetryableTaskException("SANDBOX_RUN_NOT_FOUND"));
        if (run.isTerminal()) {
            return new HandlerResult(
                    Map.of("runId", run.publicId(), "status", run.runStatus().name()));
        }
        if (run.runStatus() == SandboxRunStatus.PENDING) {
            run = claimAndMarkDispatching(run, context);
        }
        if (run.runStatus() != SandboxRunStatus.DISPATCHING) {
            throw new NonRetryableTaskException("SANDBOX_RUN_NOT_DISPATCHABLE");
        }
        context.requireValidLease();
        try {
            SandboxControllerClient.DispatchResult dispatched = controllerClient.dispatch(run);
            context.requireValidLease();
            metrics.recordSandboxRunEvent("dispatched", run.mode().name(), run.toolId(), "success");
            return new HandlerResult(Map.of(
                    "runId", run.publicId(),
                    "controllerRunId", dispatched.controllerRunId(),
                    "controllerPhase", dispatched.phase(),
                    "status", "DISPATCHED"));
        } catch (SandboxControllerClientException ex) {
            metrics.recordSandboxRunEvent(
                    "dispatch", run.mode().name(), run.toolId(), ex.retryable() ? "deferred" : ex.getMessage());
            if (!ex.retryable()) {
                failOwnedRun(run, context, ex);
                throw new NonRetryableTaskException(ex.getMessage());
            }
            throw ex;
        }
    }

    private SandboxRunRecord claimAndMarkDispatching(SandboxRunRecord run, AsyncTaskContext context) {
        Instant now = clock.instant();
        SandboxRunRecord claimed = repository
                .claimByPublicId(run.publicId(), context.ownerToken(), now, runLeaseDuration())
                .orElseGet(() -> repository
                        .findByPublicId(run.publicId())
                        .orElseThrow(() -> new NonRetryableTaskException("SANDBOX_RUN_NOT_FOUND")));
        if (claimed.isTerminal() || claimed.runStatus() == SandboxRunStatus.DISPATCHING) {
            return claimed;
        }
        if (claimed.runStatus() != SandboxRunStatus.PENDING
                || !context.ownerToken().equals(claimed.ownerToken())) {
            throw new SandboxControllerClientException("SANDBOX_RUN_CLAIM_CONFLICT", true, 0);
        }
        context.requireValidLease();
        if (!repository.markDispatching(
                claimed.publicId(),
                context.ownerToken(),
                claimed.fencingToken(),
                claimed.version(),
                claimed.publicId(),
                now)) {
            throw new SandboxControllerClientException("SANDBOX_RUN_DISPATCH_FENCE_REJECTED", true, 0);
        }
        return repository
                .findByPublicId(claimed.publicId())
                .orElseThrow(() -> new NonRetryableTaskException("SANDBOX_RUN_NOT_FOUND"));
    }

    private void failOwnedRun(
            SandboxRunRecord run, AsyncTaskContext context, SandboxControllerClientException failure) {
        if (!context.ownerToken().equals(run.ownerToken()) || run.runStatus() != SandboxRunStatus.DISPATCHING) {
            return;
        }
        repository.fail(
                run.publicId(),
                context.ownerToken(),
                run.fencingToken(),
                run.version(),
                SandboxRunStatus.FAILED,
                failure.getMessage(),
                "Sandbox Controller rejected the dispatch request",
                clock.instant());
    }

    private Duration runLeaseDuration() {
        long readMillis = Math.max(1, properties.getSandbox().getControllerReadTimeoutMillis());
        return Duration.ofSeconds(Math.max(30, (readMillis + 999) / 1000 + 10));
    }
}
