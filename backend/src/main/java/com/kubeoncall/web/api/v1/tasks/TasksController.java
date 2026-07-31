package com.kubeoncall.web.api.v1.tasks;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.TaskPermissionPolicy;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * Read-only status view for asynchronous workflow tasks.
 *
 * <p>Lease ownership, fencing tokens and request payloads remain internal worker capabilities and
 * are never returned by this API.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TasksController {

    private final ObjectProvider<AsyncTaskRepository> repositoryProvider;
    private final V1Security security;

    public TasksController(ObjectProvider<AsyncTaskRepository> repositoryProvider, V1Security security) {
        this.repositoryProvider = repositoryProvider;
        this.security = security;
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskView> detail(@PathVariable String taskId) {
        security.requireAuthenticated();
        AsyncTaskRecord task = repository().findByPublicId(taskId).orElseThrow(() -> notFound(taskId));
        security.requirePermission(TaskPermissionPolicy.requiredPermission(task.taskType(), task.resourceType()));
        return ApiResponse.ok(TaskView.from(task), RequestIdFilter.currentRequestId());
    }

    /** Cancels a queued/retrying task or cooperatively stops a running task at its next safe boundary. */
    @DeleteMapping("/{taskId}")
    public ApiResponse<TaskView> cancel(@PathVariable String taskId) {
        security.requireAuthenticated();
        AsyncTaskRecord task = repository().findByPublicId(taskId).orElseThrow(() -> notFound(taskId));
        security.requirePermission(TaskPermissionPolicy.requiredPermission(task.taskType(), task.resourceType()));
        if (!repository().cancel(taskId, Instant.now())) {
            AsyncTaskRecord current = repository().findByPublicId(taskId).orElseThrow(() -> notFound(taskId));
            if (!"CANCELLED".equals(current.status())) {
                throw V1ApiException.conflict(V1ApiErrorCode.CONFLICT, "Async task is already terminal: " + taskId);
            }
            task = current;
        } else {
            task = repository().findByPublicId(taskId).orElseThrow(() -> notFound(taskId));
        }
        return ApiResponse.ok(TaskView.from(task), RequestIdFilter.currentRequestId());
    }

    private AsyncTaskRepository repository() {
        AsyncTaskRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Async task read model is not available");
        }
        return repository;
    }

    private static V1ApiException notFound(String taskId) {
        return V1ApiException.notFound("Async task not found: " + taskId);
    }

    public record TaskView(
            String id,
            String taskType,
            String status,
            String stage,
            Integer progressPercent,
            String resourceId,
            String errorCode,
            String errorSummary,
            Instant createdAt,
            Instant finishedAt,
            long version) {

        private static TaskView from(AsyncTaskRecord record) {
            return new TaskView(
                    record.publicId(),
                    record.taskType(),
                    record.status(),
                    record.stage(),
                    record.progress(),
                    record.resourcePublicId(),
                    record.errorCode(),
                    record.errorSummary(),
                    record.createdAt(),
                    record.finishedAt(),
                    record.version());
        }
    }
}
