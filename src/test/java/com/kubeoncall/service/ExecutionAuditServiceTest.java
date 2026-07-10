package com.kubeoncall.service;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;
import com.kubeoncall.domain.audit.ExecutionRequestType;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskPlan;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.service.audit.ExecutionAuditRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExecutionAuditServiceTest {

    @Test
    void shouldPersistGraphExecutionMetadata() {
        ExecutionAuditRepository repository = mock(ExecutionAuditRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutionAuditService auditService = new ExecutionAuditService(repository, metricsService);

        GraphState state = new GraphState();
        state.setExecutionId("exec-1");
        state.setUserRequest("describe payment pod");
        state.setStatus(GraphStatus.SUCCESS);
        state.getContext().put("sessionId", "session-1");
        state.getContext().put("plannerSource", "rules");
        state.getContext().put("plannerIntent", "diagnose");
        state.getContext().put("activatedSkillIds", List.of("payment-oom-triage"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");
        state.getContext().put("injectedMemoryCount", 2);
        state.getContext().put("executorPayload", Map.of(
                "executorKind", "kubernetes",
                "action", "describeResource",
                "toolName", "kubernetes.describeResource",
                "dryRun", true
        ));
        state.getContext().put("executorResult", Map.of("status", "success", "message", "described"));

        Task task = new Task(
                "task-1",
                "describe pod",
                TaskType.QUERY_METRICS,
                RiskLevel.LOW,
                "payment-service",
                Map.of(),
                null
        );
        state.setTaskPlan(new TaskPlan("exec-1", "describe payment pod", List.of(task), Instant.now(), false));
        state.setCurrentTask(task);
        state.addNodeResult(new NodeResult("executorExecuteNode", NodeStatus.SUCCESS, "ok", Map.of()));

        auditService.recordGraphExecution(ExecutionRequestType.ASK, state, Instant.now().minusMillis(10));

        org.mockito.ArgumentCaptor<ExecutionAuditRecord> recordCaptor = org.mockito.ArgumentCaptor.forClass(ExecutionAuditRecord.class);
        verify(repository).save(recordCaptor.capture());
        Map<String, Object> metadata = recordCaptor.getValue().metadata();
        assertEquals("session-1", metadata.get("sessionId"));
        assertEquals("rules", metadata.get("plannerSource"));
        assertEquals(List.of("payment-oom-triage"), metadata.get("activatedSkillIds"));
        assertEquals(2, metadata.get("injectedMemoryCount"));
        assertEquals("task-1", metadata.get("currentTaskId"));
        assertEquals("QUERY_METRICS", metadata.get("currentTaskType"));
        assertEquals("kubernetes", metadata.get("executorKind"));
        assertEquals("describeResource", metadata.get("executorAction"));
        assertEquals("success", metadata.get("executorResultStatus"));
        verify(metricsService).recordGraphExecution(eq("ASK"), anyString(), eq(false), eq(false));
    }

    @Test
    void shouldPersistMemoryOperationAudit() {
        ExecutionAuditRepository repository = mock(ExecutionAuditRepository.class);
        ExecutionAuditService auditService = new ExecutionAuditService(
                repository, mock(KubeOnCallMetricsService.class));

        auditService.recordMemoryOperation(
                "restore", "success", "restored memory-1", Instant.now(),
                Map.of("memoryId", "memory-1"));

        org.mockito.ArgumentCaptor<ExecutionAuditRecord> recordCaptor =
                org.mockito.ArgumentCaptor.forClass(ExecutionAuditRecord.class);
        verify(repository).save(recordCaptor.capture());
        assertEquals(ExecutionRequestType.MEMORY, recordCaptor.getValue().requestType());
        assertEquals("restore", recordCaptor.getValue().metadata().get("memoryOperation"));
        assertEquals("memory-1", recordCaptor.getValue().metadata().get("memoryId"));
    }
}
