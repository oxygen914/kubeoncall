package com.kubeoncall.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;
import com.kubeoncall.workflow.execution.WorkflowNodeExecutionRecord;

class AlarmWorkflowFactRecorderTest {

    @Test
    void persistsExecutionNodesIncidentLinkAuditAndOutbox() {
        WorkflowExecutionRepository executions = mock(WorkflowExecutionRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OperationAuditWriter auditWriter = mock(OperationAuditWriter.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        AlarmWorkflowFactRecorder recorder =
                new AlarmWorkflowFactRecorder(executions, jdbcTemplate, auditWriter, outboxWriter);
        Instant startedAt = Instant.parse("2026-07-20T08:00:00Z");
        WorkflowExecutionRecord running = execution("RUNNING", 1L, startedAt, null);
        WorkflowExecutionRecord finished = execution("FAILED", 2L, startedAt, startedAt.plusSeconds(5));

        when(jdbcTemplate.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(),
                        eq("fp-workflow-fact")))
                .thenReturn(List.of("alm_fact_1"));
        when(executions.create(any())).thenReturn(running);
        AtomicInteger nodeSequence = new AtomicInteger();
        when(executions.createNode(any())).thenAnswer(invocation -> {
            WorkflowExecutionRepository.CreateNodeExecution command = invocation.getArgument(0);
            int number = nodeSequence.incrementAndGet();
            return node(number, "node_" + number, command.nodeName(), command.attempt(), command.startedAt());
        });
        when(executions.updateNode(anyString(), eq(1L), anyString(), any(), any(), any(), any()))
                .thenReturn(true);
        when(executions.updateStatus(
                        eq("exe_fact_1"),
                        eq(1L),
                        eq("FAILED"),
                        eq("workflow failed"),
                        eq("ALARM_WORKFLOW_FAILED"),
                        eq("diagnosis failed"),
                        eq(startedAt),
                        any()))
                .thenReturn(true);
        when(executions.findByPublicId("exe_fact_1")).thenReturn(Optional.of(finished));

        recorder.record(request(startedAt));

        ArgumentCaptor<WorkflowExecutionRepository.CreateExecution> executionCommand =
                ArgumentCaptor.forClass(WorkflowExecutionRepository.CreateExecution.class);
        verify(executions).create(executionCommand.capture());
        assertThat(executionCommand.getValue()).satisfies(command -> {
            assertThat(command.type()).isEqualTo("ALARM");
            assertThat(command.triggerType()).isEqualTo("ALARM");
            assertThat(command.triggerPublicId()).isEqualTo("alm_fact_1");
            assertThat(command.status()).isEqualTo("RUNNING");
            assertThat(command.riskLevel()).isEqualTo("HIGH");
            assertThat(command.actorType()).isEqualTo("SYSTEM");
            assertThat(command.startedAt()).isEqualTo(startedAt);
        });

        ArgumentCaptor<WorkflowExecutionRepository.CreateNodeExecution> nodeCommands =
                ArgumentCaptor.forClass(WorkflowExecutionRepository.CreateNodeExecution.class);
        verify(executions, org.mockito.Mockito.times(2)).createNode(nodeCommands.capture());
        assertThat(nodeCommands.getAllValues())
                .extracting(
                        WorkflowExecutionRepository.CreateNodeExecution::nodeName,
                        WorkflowExecutionRepository.CreateNodeExecution::attempt,
                        WorkflowExecutionRepository.CreateNodeExecution::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("diagnosis", 1, "RUNNING"),
                        org.assertj.core.groups.Tuple.tuple("diagnosis", 2, "RUNNING"));
        verify(executions)
                .updateNode(eq("node_1"), eq(1L), eq("SUCCEEDED"), eq("diagnosed"), isNull(), isNull(), any());
        verify(executions)
                .updateNode(
                        eq("node_2"),
                        eq(1L),
                        eq("FAILED"),
                        eq("diagnosis failed"),
                        eq("FAILURE"),
                        eq("diagnosis failed"),
                        any());
        verify(jdbcTemplate).update(contains("latest_execution_id"), eq(91L), eq("alm_fact_1"));

        ArgumentCaptor<OperationAuditWriter.AuditEntry> audit =
                ArgumentCaptor.forClass(OperationAuditWriter.AuditEntry.class);
        verify(auditWriter).write(audit.capture());
        assertThat(audit.getValue()).satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("workflow.alarm.complete");
            assertThat(entry.resourceType()).isEqualTo("execution");
            assertThat(entry.resourcePublicId()).isEqualTo("exe_fact_1");
            assertThat(entry.result()).isEqualTo("FAILURE");
            assertThat(entry.after())
                    .containsEntry("status", "FAILED")
                    .containsEntry("version", 2L)
                    .containsEntry("alarmId", "alm_fact_1");
        });

        ArgumentCaptor<OutboxWriter.OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxWriter.OutboxEvent.class);
        verify(outboxWriter).enqueue(outbox.capture());
        assertThat(outbox.getValue()).satisfies(event -> {
            assertThat(event.aggregateType()).isEqualTo("execution");
            assertThat(event.aggregatePublicId()).isEqualTo("exe_fact_1");
            assertThat(event.eventType()).isEqualTo("execution.updated");
            assertThat(event.payload())
                    .containsEntry("executionId", "exe_fact_1")
                    .containsEntry("type", "ALARM")
                    .containsEntry("status", "FAILED")
                    .containsEntry("version", 2L);
        });
    }

    private static AlarmWorkflowAuditRecorder.AuditRequest request(Instant startedAt) {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "delivery-fact-1",
                "fp-workflow-fact",
                "NodeCpuHigh",
                "alertmanager",
                "critical",
                AlarmSeverity.P1,
                AlarmResourceType.NODE,
                "worker-01",
                "prod",
                null,
                null,
                "cpu_usage",
                95.0,
                90.0,
                "%",
                "5m",
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                startedAt,
                "CPU high",
                Map.of());
        return new AlarmWorkflowAuditRecorder.AuditRequest(
                "legacy-execution-1",
                "FAILED",
                false,
                false,
                "workflow failed",
                "diagnosis failed",
                List.of("prometheus"),
                startedAt,
                event,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "test"),
                null,
                null,
                null,
                List.of(
                        new NodeResult("diagnosis", NodeStatus.SUCCESS, "diagnosed", Map.of()),
                        new NodeResult("diagnosis", NodeStatus.FAILURE, "diagnosis failed", Map.of())),
                Map.of());
    }

    private static WorkflowExecutionRecord execution(
            String status, long version, Instant startedAt, Instant finishedAt) {
        return new WorkflowExecutionRecord(
                91L,
                "exe_fact_1",
                "ALARM",
                "ALARM",
                "alm_fact_1",
                "legacy-execution-1:1",
                status,
                "HIGH",
                "workflow failed",
                "workflow failed",
                "FAILED".equals(status) ? "ALARM_WORKFLOW_FAILED" : null,
                "FAILED".equals(status) ? "diagnosis failed" : null,
                "SYSTEM",
                null,
                null,
                "awf_request_1",
                null,
                null,
                startedAt,
                finishedAt,
                version,
                startedAt,
                finishedAt == null ? startedAt : finishedAt);
    }

    private static WorkflowNodeExecutionRecord node(
            long id, String publicId, String nodeName, int attempt, Instant startedAt) {
        return new WorkflowNodeExecutionRecord(
                id,
                publicId,
                91L,
                "exe_fact_1",
                nodeName,
                "ALARM_NODE",
                attempt,
                "RUNNING",
                null,
                null,
                null,
                null,
                startedAt,
                null,
                null,
                1L,
                startedAt,
                startedAt);
    }
}
