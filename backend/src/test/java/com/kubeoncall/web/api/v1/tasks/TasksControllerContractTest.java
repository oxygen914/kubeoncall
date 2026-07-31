package com.kubeoncall.web.api.v1.tasks;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Security;

class TasksControllerContractTest {

    private MockMvc mockMvc;
    private AsyncTaskRepository repository;
    private V1Security security;

    @BeforeEach
    void setUp() {
        repository = mock(AsyncTaskRepository.class);
        security = mock(V1Security.class);
        when(repository.isAvailable()).thenReturn(true);
        mockMvc = MockMvcBuilders.standaloneSetup(new TasksController(provider(repository), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void detailReturnsSafeTaskStatusProjection() throws Exception {
        when(repository.findByPublicId("task_1")).thenReturn(Optional.of(task()));

        mockMvc.perform(get("/api/v1/tasks/task_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("task_1"))
                .andExpect(jsonPath("$.data.taskType").value("APPROVAL_RESUME"))
                .andExpect(jsonPath("$.data.progressPercent").value(45))
                .andExpect(jsonPath("$.data.resourceId").value("exe_1"))
                .andExpect(jsonPath("$.data.ownerToken").doesNotExist())
                .andExpect(jsonPath("$.data.fencingToken").doesNotExist())
                .andExpect(jsonPath("$.data.request").doesNotExist());
        verify(security).requirePermission(PermissionCode.EXECUTION_READ);
    }

    @Test
    void detailReturnsNotFoundEnvelope() throws Exception {
        when(repository.findByPublicId("task_missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/tasks/task_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void unavailableRepositoryReturnsServiceUnavailable() throws Exception {
        when(repository.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/tasks/task_1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void detailRequiresExecutionReadPermission() throws Exception {
        when(repository.findByPublicId("task_1")).thenReturn(Optional.of(task()));
        when(security.requirePermission(PermissionCode.EXECUTION_READ)).thenThrow(V1ApiException.forbidden("no"));

        mockMvc.perform(get("/api/v1/tasks/task_1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void knowledgeTaskRequiresKnowledgeReadRatherThanExecutionRead() throws Exception {
        AsyncTaskRecord knowledgeTask = task("KNOWLEDGE_IMPORT", "KNOWLEDGE_IMPORT", "imp_1");
        when(repository.findByPublicId("task_knowledge")).thenReturn(Optional.of(knowledgeTask));

        mockMvc.perform(get("/api/v1/tasks/task_knowledge")).andExpect(status().isOk());

        verify(security).requirePermission(PermissionCode.KNOWLEDGE_READ);
    }

    private static AsyncTaskRecord task() {
        return task("APPROVAL_RESUME", "EXECUTION", "exe_1");
    }

    private static AsyncTaskRecord task(String taskType, String resourceType, String resourceId) {
        return new AsyncTaskRecord(
                81,
                "task_1",
                taskType,
                resourceType,
                resourceId,
                "dedupe-secret",
                "RUNNING",
                "resume",
                45,
                Map.of("approvalId", "apr_1"),
                Map.of(),
                null,
                null,
                "owner-secret",
                Instant.parse("2026-07-20T01:10:00Z"),
                4,
                2,
                5,
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:01:00Z"),
                null,
                "req_1",
                "trace_1",
                3,
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:01:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
