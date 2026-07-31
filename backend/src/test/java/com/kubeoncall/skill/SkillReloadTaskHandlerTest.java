package com.kubeoncall.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.skill.mysql.SkillStateRecord;
import com.kubeoncall.skill.mysql.SkillStateRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;

class SkillReloadTaskHandlerTest {

    private SkillRegistry registry;
    private SkillStateRepository repository;
    private SkillReloadTaskHandler handler;

    @BeforeEach
    void setUp() {
        registry = mock(SkillRegistry.class);
        repository = mock(SkillStateRepository.class);
        handler = new SkillReloadTaskHandler(registry, repository);
    }

    @Test
    void successfulReloadPersistsChecksumMetadataAndPreservesDisabledState() {
        Skill skill = skill();
        SkillIndexEntry index = new SkillIndexEntry(
                skill.id(),
                skill.name(),
                skill.description(),
                skill.triggers(),
                skill.services(),
                skill.resourceTypes(),
                skill.alertNames(),
                skill.metricNames(),
                skill.runbookIds(),
                skill.categories(),
                List.of("QUERY_METRICS"),
                skill.tags(),
                "HIGH",
                skill.version(),
                "PROJECT",
                skill.skillPath(),
                true);
        when(registry.reload()).thenReturn(new SkillRegistry.ReloadResult(1, 0, List.of()));
        when(registry.index()).thenReturn(List.of(index));
        when(registry.findByIdIncludingDisabled(skill.id())).thenReturn(Optional.of(skill));
        when(repository.find(skill.id())).thenReturn(Optional.of(existingDisabled()));

        AsyncTaskHandler.HandlerResult result = handler.handle(context(true));

        assertThat(result.result())
                .containsEntry("loaded", 1)
                .containsEntry("projectOverrides", 0)
                .containsEntry("persisted", 1)
                .containsEntry("skillIds", List.of("diagnose-node"));
        verify(registry).disable("diagnose-node");
        ArgumentCaptor<SkillStateRepository.UpsertSkillState> upsert =
                ArgumentCaptor.forClass(SkillStateRepository.UpsertSkillState.class);
        verify(repository).upsert(upsert.capture());
        assertThat(upsert.getValue().publicId()).isEqualTo("skl_1");
        assertThat(upsert.getValue().enabled()).isFalse();
        assertThat(upsert.getValue().checksum())
                .isEqualTo("1f42523adcc9a31dd7b8ab3b36f098c6a87e7fc7e0760fb3ea7be7cb93420d0d");
        assertThat(upsert.getValue().skillVersion()).isEqualTo("1.2.0");
        assertThat(upsert.getValue().sourceLocation()).isEqualTo(skill.skillPath());
        assertThat(upsert.getValue().updatedBy()).isEqualTo(7L);
        assertThat(upsert.getValue().metadata())
                .containsEntry("name", "Diagnose node")
                .containsEntry("source", "PROJECT")
                .containsEntry("applicableTasks", List.of("QUERY_METRICS"))
                .containsEntry("alertNames", List.of("NodeCPUHigh"))
                .containsEntry("metricNames", List.of("node.cpu.usage_percent"))
                .containsEntry("runbookIds", List.of("runbook-host-cpu-high"))
                .containsEntry("categories", List.of("node-monitoring"))
                .containsEntry("tags", List.of("kubernetes", "node"))
                .containsEntry("maxRisk", "HIGH")
                .containsEntry("custom", "value");
    }

    @Test
    void reloadErrorsAreRetriedByWorkerWithoutPersistingPartialProjection() {
        when(registry.reload())
                .thenReturn(
                        new SkillRegistry.ReloadResult(1, 0, List.of("PROJECT:broken/SKILL.md: skill load failed")));

        assertThatThrownBy(() -> handler.handle(context(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill load failed");

        verify(repository, never()).upsert(any());
    }

    @Test
    void invalidLeaseStopsBeforeRegistryReload() {
        assertThatThrownBy(() -> handler.handle(context(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease");

        verify(registry, never()).reload();
    }

    private static Skill skill() {
        return new Skill(
                "diagnose-node",
                "Diagnose node",
                "1.2.0",
                SkillSource.PROJECT,
                "file:/skills/diagnose-node/SKILL.md",
                "Diagnose Kubernetes node alerts",
                List.of("node alert"),
                List.of("kubernetes"),
                List.of("node"),
                List.of("NodeCPUHigh"),
                List.of("node.cpu.usage_percent"),
                List.of("runbook-host-cpu-high"),
                List.of("node-monitoring"),
                List.of(TaskType.QUERY_METRICS),
                List.of("kubernetes", "node"),
                RiskLevel.HIGH,
                List.of("kubectl"),
                "skill body",
                Map.of("custom", "value"));
    }

    private static SkillStateRecord existingDisabled() {
        return new SkillStateRecord(
                "skl_1",
                "diagnose-node",
                "1.1.0",
                "a".repeat(64),
                "old",
                false,
                "DISABLED",
                null,
                Map.of(),
                Instant.parse("2026-07-20T00:00:00Z"),
                3,
                Instant.parse("2026-07-19T00:00:00Z"),
                Instant.parse("2026-07-20T00:00:00Z"));
    }

    private static AsyncTaskContext context(boolean leaseValid) {
        try {
            Constructor<AsyncTaskContext> constructor = AsyncTaskContext.class.getDeclaredConstructor(
                    AsyncTaskRecord.class, String.class, BooleanSupplier.class);
            constructor.setAccessible(true);
            return constructor.newInstance(task(), "worker_1", (BooleanSupplier) () -> leaseValid);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static AsyncTaskRecord task() {
        Instant now = Instant.parse("2026-07-21T03:00:00Z");
        return new AsyncTaskRecord(
                1,
                "tsk_1",
                "SKILL_RELOAD",
                "skill",
                "skill_registry",
                "skill-reload:1",
                "RUNNING",
                "QUEUED",
                0,
                Map.of("requestedBy", 7L),
                Map.of(),
                null,
                null,
                "worker_1",
                now.plusSeconds(60),
                2,
                1,
                5,
                now,
                now,
                null,
                "req_1",
                "trace_1",
                2,
                now,
                now);
    }
}
