package com.kubeoncall.task.worker;

import org.springframework.stereotype.Component;

import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.task.AsyncTaskRecord;

/** Emits a low-cardinality signal whenever a durable task crosses the request-to-worker boundary. */
@Component
public class TaskCorrelationMetricsListener implements AsyncTaskLifecycleListener {

    private final KubeOnCallMetricsService metrics;

    public TaskCorrelationMetricsListener(KubeOnCallMetricsService metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onTransition(AsyncTaskRecord task, AsyncTaskWorker.RunResult result) {
        record(task);
    }

    @Override
    public void onLeaseLost(AsyncTaskRecord task, AsyncTaskWorker.RunResult result) {
        record(task);
    }

    private void record(AsyncTaskRecord task) {
        metrics.recordRequestCorrelation(
                "async_task", task.requestId() != null && !task.requestId().isBlank());
    }
}
