package com.kubeoncall.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.skill.mysql.SkillStateRecord;
import com.kubeoncall.skill.mysql.SkillStateRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;

class SkillGovernanceServiceTest {

    private SkillStateRepository stateRepository;
    private SkillRegistry registry;
    private AsyncTaskRepository taskRepository;
    private OperationAuditWriter auditWriter;
    private OutboxWriter outboxWriter;
    private SkillGovernanceService service;

    @BeforeEach
    void setUp() {
        stateRepository = mock(SkillStateRepository.class);
        registry = mock(SkillRegistry.class);
        taskRepository = mock(AsyncTaskRepository.class);
        auditWriter = mock(OperationAuditWriter.class);
        outboxWriter = mock(OutboxWriter.class);
        when(stateRepository.isAvailable()).thenReturn(true);
        when(taskRepository.isAvailable()).thenReturn(true);
        when(auditWriter.isAvailable()).thenReturn(true);
        when(outboxWriter.isAvailable()).thenReturn(true);
        service = new SkillGovernanceService(stateRepository, registry, taskRepository, auditWriter, outboxWriter);
    }

    @Test
    void versionedDisableUpdatesProjectionRuntimeAndAudit() {
        SkillStateRecord before = state(true, "LOADED", 4);
        SkillStateRecord after = state(false, "DISABLED", 5);
        when(stateRepository.find("diagnose-node")).thenReturn(Optional.of(before), Optional.of(after));
        when(registry.findByIdIncludingDisabled("diagnose-node")).thenReturn(Optional.of(skill()));
        when(stateRepository.setEnabled("diagnose-node", 4, false, 7L)).thenReturn(true);

        SkillStateRecord result = service.setEnabled(new SkillGovernanceService.ChangeEnabledCommand(
                "diagnose-node", 4, false, 7L, "Operator", "req_1", "127.0.0.1", "JUnit"));

        assertThat(result).isEqualTo(after);
        verify(registry).disable("diagnose-node");
        ArgumentCaptor<OperationAuditWriter.AuditEntry> audit =
                ArgumentCaptor.forClass(OperationAuditWriter.AuditEntry.class);
        verify(auditWriter).write(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("skill.enabled.update");
        assertThat(audit.getValue().before()).containsEntry("enabled", true);
        assertThat(audit.getValue().after()).containsEntry("enabled", false);
        verify(outboxWriter, never()).enqueue(any());
    }

    @Test
    void staleVersionFailsBeforeAnyMutation() {
        when(stateRepository.find("diagnose-node")).thenReturn(Optional.of(state(true, "LOADED", 6)));

        assertThatThrownBy(() -> service.setEnabled(new SkillGovernanceService.ChangeEnabledCommand(
                        "diagnose-node", 5, false, 7L, "Operator", "req_1", null, null)))
                .isInstanceOf(SkillGovernanceException.class)
                .satisfies(error -> assertThat(((SkillGovernanceException) error).code())
                        .isEqualTo(SkillGovernanceException.Code.VERSION_CONFLICT));

        verify(stateRepository, never()).setEnabled(any(), anyLong(), anyBoolean(), any());
        verify(auditWriter, never()).write(any());
        verify(outboxWriter, never()).enqueue(any());
    }

    @Test
    void reloadCreatesDurableTaskAuditAndTaskCreatedEvent() {
        Instant now = Instant.parse("2026-07-21T03:00:00Z");
        AsyncTaskRecord task = task(now);
        when(taskRepository.create(any())).thenReturn(task);

        AsyncTaskRecord result = service.requestReload(new SkillGovernanceService.ReloadCommand(
                7L, "Operator", now, "req_1", "trace_1", "127.0.0.1", "JUnit"));

        assertThat(result).isEqualTo(task);
        ArgumentCaptor<AsyncTaskRepository.CreateTask> create =
                ArgumentCaptor.forClass(AsyncTaskRepository.CreateTask.class);
        verify(taskRepository).create(create.capture());
        assertThat(create.getValue().taskType()).isEqualTo("SKILL_RELOAD");
        assertThat(create.getValue().resourceType()).isEqualTo("skill");
        assertThat(create.getValue().stage()).isEqualTo("QUEUED");
        assertThat(create.getValue().request()).containsEntry("requestedBy", 7L);
        ArgumentCaptor<OperationAuditWriter.AuditEntry> audit =
                ArgumentCaptor.forClass(OperationAuditWriter.AuditEntry.class);
        verify(auditWriter).write(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("skill.reload.request");
        ArgumentCaptor<OutboxWriter.OutboxEvent> event = ArgumentCaptor.forClass(OutboxWriter.OutboxEvent.class);
        verify(outboxWriter).enqueue(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("task.created");
        assertThat(event.getValue().payload()).containsEntry("taskType", "SKILL_RELOAD");
    }

    private static Skill skill() {
        return new Skill(
                "diagnose-node",
                "Diagnose node",
                "1.2.0",
                SkillSource.PROJECT,
                "file:/skills/diagnose-node/SKILL.md",
                "Diagnose Kubernetes node alerts",
                java.util.List.of("node alert"),
                java.util.List.of("kubernetes"),
                java.util.List.of("node"),
                null,
                java.util.List.of("kubectl"),
                "body",
                Map.of());
    }

    private static SkillStateRecord state(boolean enabled, String status, long version) {
        return new SkillStateRecord(
                "skl_1",
                "diagnose-node",
                "1.2.0",
                "a".repeat(64),
                "file:/skills/diagnose-node/SKILL.md",
                enabled,
                status,
                null,
                Map.of("name", "Diagnose node"),
                Instant.parse("2026-07-21T02:00:00Z"),
                version,
                Instant.parse("2026-07-21T01:00:00Z"),
                Instant.parse("2026-07-21T02:00:00Z"));
    }

    private static AsyncTaskRecord task(Instant now) {
        return new AsyncTaskRecord(
                1,
                "tsk_1",
                "SKILL_RELOAD",
                "skill",
                "skill_registry",
                "skill-reload:1",
                "PENDING",
                "QUEUED",
                0,
                Map.of("requestedBy", 7L),
                Map.of(),
                null,
                null,
                null,
                null,
                0,
                0,
                5,
                now,
                null,
                null,
                "req_1",
                "trace_1",
                1,
                now,
                now);
    }
}
