package com.kubeoncall.memory;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.memory.mysql.MemoryExtractionTaskRecord;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.worker.AsyncTaskLifecycleListener;
import com.kubeoncall.task.worker.AsyncTaskWorker;

/** Mirrors terminal worker exhaustion into the memory-extraction read model. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MemoryExtractionDeadLetterListener implements AsyncTaskLifecycleListener {

    private final MemoryExtractionTaskRepository repository;

    public MemoryExtractionDeadLetterListener(MemoryExtractionTaskRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onTransition(AsyncTaskRecord claimedTask, AsyncTaskWorker.RunResult result) {
        if (!MemoryGovernanceService.EXTRACTION_TASK_TYPE.equals(claimedTask.taskType())
                || result.outcome() != AsyncTaskWorker.Outcome.DEAD_LETTERED) {
            return;
        }
        MemoryExtractionTaskRecord extraction =
                repository.find(claimedTask.resourcePublicId()).orElse(null);
        if (extraction != null && !"SUCCEEDED".equals(extraction.status())) {
            repository.fail(
                    extraction.publicId(),
                    extraction.version(),
                    result.errorCode(),
                    result.errorSummary(),
                    Instant.now());
        }
    }
}
