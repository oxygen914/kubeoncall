package com.kubeoncall.migration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;

/** Creates durable, operator-visible migration backfill tasks rather than running a Redis scan in HTTP. */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MigrationBackfillTaskSubmissionService {

    public static final String TASK_TYPE = "MIGRATION_BACKFILL";
    public static final String RESOURCE_TYPE = "MIGRATION";

    private final AsyncTaskRepository taskRepository;
    private final KubeOnCallProperties properties;

    public MigrationBackfillTaskSubmissionService(AsyncTaskRepository taskRepository, KubeOnCallProperties properties) {
        this.taskRepository = taskRepository;
        this.properties = properties;
    }

    public AsyncTaskRecord submit(MigrationBackfillTaskHandler.Domain domain, Boolean dryRun, String requestId) {
        if (!taskRepository.isAvailable()) {
            throw new IllegalStateException("Async task repository is unavailable");
        }
        boolean effectiveDryRun = dryRun == null ? properties.getDataMigration().isBackfillDryRun() : dryRun;
        long timeoutSeconds = Math.max(1L, properties.getDataMigration().getBackfillTaskTimeoutSeconds());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("domain", domain.name());
        request.put("dryRun", effectiveDryRun);
        request.put("timeoutSeconds", timeoutSeconds);
        request.put("submittedAt", Instant.now().toString());
        return taskRepository.create(new AsyncTaskRepository.CreateTask(
                null,
                TASK_TYPE,
                RESOURCE_TYPE,
                domain.name(),
                "migration:" + domain.name() + ":" + UUID.randomUUID(),
                "queued",
                request,
                3,
                Instant.now(),
                requestId,
                requestId));
    }
}
