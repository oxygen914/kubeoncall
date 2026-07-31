package com.kubeoncall.task.worker;

import com.kubeoncall.task.AsyncTaskRecord;

/**
 * Observes durable asynchronous-task transitions after the repository accepted them.
 *
 * <p>Listeners are best-effort projections. They must not be used as the source of truth and a
 * listener failure must not turn an already persisted task transition into a worker failure.
 */
@FunctionalInterface
public interface AsyncTaskLifecycleListener {

    void onTransition(AsyncTaskRecord claimedTask, AsyncTaskWorker.RunResult result);

    /** Observes an ownership-fence or heartbeat loss without publishing a false transition. */
    default void onLeaseLost(AsyncTaskRecord claimedTask, AsyncTaskWorker.RunResult result) {}
}
