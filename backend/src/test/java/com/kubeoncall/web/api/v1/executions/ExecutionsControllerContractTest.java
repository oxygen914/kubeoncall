package com.kubeoncall.web.api.v1.executions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Security;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;
import com.kubeoncall.workflow.execution.WorkflowNodeExecutionRecord;

class ExecutionsControllerContractTest {

    private MockMvc mockMvc;
    private WorkflowExecutionRepository repository;
    private V1Security security;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowExecutionRepository.class);
        security = mock(V1Security.class);
        when(repository.isAvailable()).thenReturn(true);
        mockMvc = MockMvcBuilders.standaloneSetup(new ExecutionsController(provider(repository), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void listReturnsPagedPublicProjectionAndForwardsFilters() throws Exception {
        when(repository.list(any())).thenReturn(new WorkflowExecutionRepository.ExecutionPage(List.of(execution()), 1));

        mockMvc.perform(get("/api/v1/executions")
                        .queryParam("page", "2")
                        .queryParam("size", "500")
                        .queryParam("status", "RUNNING, WAITING_APPROVAL")
                        .queryParam("type", "ASK")
                        .queryParam("alarmId", "alm_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("exe_1"))
                .andExpect(jsonPath("$.data[0].triggerId").value("alm_1"))
                .andExpect(jsonPath("$.data[0].durationMs").value(60000))
                .andExpect(jsonPath("$.data[0].dedupeKey").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(2))
                .andExpect(jsonPath("$.page.size").value(200))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.meta.requestId").exists());

        ArgumentCaptor<WorkflowExecutionRepository.ExecutionQuery> query =
                ArgumentCaptor.forClass(WorkflowExecutionRepository.ExecutionQuery.class);
        verify(repository).list(query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue().statuses())
                .containsExactly("RUNNING", "WAITING_APPROVAL");
        org.assertj.core.api.Assertions.assertThat(query.getValue().types()).containsExactly("ASK");
        org.assertj.core.api.Assertions.assertThat(query.getValue().triggerPublicId())
                .isEqualTo("alm_1");
        verify(security).requirePermission(PermissionCode.EXECUTION_READ);
    }

    @Test
    void detailEmbedsNodeTimelineWithoutInternalKeys() throws Exception {
        when(repository.findByPublicId("exe_1")).thenReturn(Optional.of(execution()));
        when(repository.listNodes("exe_1")).thenReturn(List.of(node()));

        mockMvc.perform(get("/api/v1/executions/exe_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("exe_1"))
                .andExpect(jsonPath("$.data.currentNode").value("verifier"))
                .andExpect(jsonPath("$.data.nodes[0].id").value("node_1"))
                .andExpect(jsonPath("$.data.nodes[0].durationMs").value(60000))
                .andExpect(jsonPath("$.data.graphStateKey").doesNotExist())
                .andExpect(jsonPath("$.data.actorId").doesNotExist());
    }

    @Test
    void nodeCompatibilityEndpointRequiresExistingExecution() throws Exception {
        when(repository.findByPublicId("exe_missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/executions/exe_missing/nodes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void detailReturnsNotFoundEnvelope() throws Exception {
        when(repository.findByPublicId("exe_missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/executions/exe_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void unavailableRepositoryReturnsServiceUnavailable() throws Exception {
        when(repository.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void listRequiresExecutionReadPermission() throws Exception {
        when(security.requirePermission(PermissionCode.EXECUTION_READ)).thenThrow(V1ApiException.forbidden("no"));

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static WorkflowExecutionRecord execution() {
        return new WorkflowExecutionRecord(
                91,
                "exe_1",
                "ASK",
                "ALARM",
                "alm_1",
                "dedupe-secret",
                "RUNNING",
                "HIGH",
                "Diagnose node",
                null,
                null,
                null,
                "USER",
                7L,
                "session-secret",
                "req_1",
                "trace_1",
                "redis-secret",
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:01:00Z"),
                3,
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:01:00Z"));
    }

    private static WorkflowNodeExecutionRecord node() {
        return new WorkflowNodeExecutionRecord(
                101,
                "node_1",
                91,
                "exe_1",
                "verifier",
                "LLM",
                1,
                "SUCCEEDED",
                "input",
                "verified",
                null,
                null,
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:01:00Z"),
                60000L,
                2,
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
