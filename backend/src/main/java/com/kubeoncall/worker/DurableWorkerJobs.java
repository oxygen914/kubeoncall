package com.kubeoncall.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kubeoncall.audit.outbox.OutboxWorker;
import com.kubeoncall.task.worker.AsyncTaskWorker;

/** Lightweight scheduler adapters; worker primitives themselves stay deterministic and testable. */
@Component
public class DurableWorkerJobs {

    private static final Logger log = LoggerFactory.getLogger(DurableWorkerJobs.class);

    private final ObjectProvider<AsyncTaskWorker> asyncTaskWorkerProvider;
    private final ObjectProvider<OutboxWorker> outboxWorkerProvider;

    public DurableWorkerJobs(
            ObjectProvider<AsyncTaskWorker> asyncTaskWorkerProvider,
            ObjectProvider<OutboxWorker> outboxWorkerProvider) {
        this.asyncTaskWorkerProvider = asyncTaskWorkerProvider;
        this.outboxWorkerProvider = outboxWorkerProvider;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.worker.task.poll-millis:500}")
    public void runAsyncTask() {
        AsyncTaskWorker worker = asyncTaskWorkerProvider.getIfAvailable();
        if (worker == null) {
            return;
        }
        try {
            AsyncTaskWorker.RunResult result = worker.runOnce();
            if (result.outcome() == AsyncTaskWorker.Outcome.LEASE_LOST) {
                log.warn(
                        "Async task worker lost lease: taskId={}, message={}",
                        result.taskPublicId(),
                        result.errorSummary());
            }
        } catch (RuntimeException ex) {
            log.error("Async task worker iteration failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${kubeoncall.worker.outbox.poll-millis:500}")
    public void runOutbox() {
        OutboxWorker worker = outboxWorkerProvider.getIfAvailable();
        if (worker == null) {
            return;
        }
        try {
            OutboxWorker.RunResult result = worker.runOnce();
            if (result.leaseLost() > 0) {
                log.warn("Outbox worker lost {} event leases", result.leaseLost());
            }
        } catch (RuntimeException ex) {
            log.error("Outbox worker iteration failed", ex);
        }
    }
}
