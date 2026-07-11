package com.kubeoncall.memory;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HeuristicMemoryExtractorTest {

    @Test
    void shouldExtractKnownPitfallFromAskAnswer() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor(queue, new KubeOnCallProperties());
        GraphState state = new GraphState();
        state.setExecutionId("exec-1");
        state.setCurrentTask(new Task(
                "task-1",
                "check",
                TaskType.QUERY_METRICS,
                RiskLevel.LOW,
                "payment-service",
                Map.of(),
                new SopReference("SOP-1", "sop", "v1", "rag")
        ));

        extractor.extractFromAsk(state, "Known pitfall: avoid restarting payment-service before checking queue lag.");

        ArgumentCaptor<MemoryExtractionTask> taskCaptor = ArgumentCaptor.forClass(MemoryExtractionTask.class);
        verify(queue).enqueue(taskCaptor.capture());
        assertEquals(MemoryType.KNOWN_PITFALL, taskCaptor.getValue().memoryType());
        assertEquals("payment-service", taskCaptor.getValue().service());
    }

    @Test
    void shouldSkipKubectlAndRealtimeContent() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor(queue, new KubeOnCallProperties());

        extractor.extractFromAsk(new GraphState(), "kubectl get pods -n prod shows current value 3");

        verify(queue, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldStripVolatileAlarmSummaryBeforeRemembering() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor(queue, new KubeOnCallProperties());
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "alarm-1",
                "fp-1",
                "PodOOMKilled",
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                AlarmResourceType.POD,
                "payment-pod",
                "cluster-a",
                "prod",
                "payment-service",
                "container_memory_working_set_bytes",
                null,
                null,
                "GiB",
                "5m",
                Map.of(),
                Map.of(),
                "runbook-oom",
                null,
                Instant.now(),
                "oom",
                Map.of()
        );

        extractor.extractFromAlarm(
                event,
                "alert=PodOOMKilled; fingerprint=fp-1; latestMessage=kubectl get pod payment-pod; metric=当前值 2GiB; outcome=SUCCESS"
        );

        ArgumentCaptor<MemoryExtractionTask> taskCaptor = ArgumentCaptor.forClass(MemoryExtractionTask.class);
        verify(queue).enqueue(taskCaptor.capture());
        String content = taskCaptor.getValue().content();
        assertTrue(content.contains("fingerprint=fp-1"));
        assertTrue(content.contains("outcome=SUCCESS"));
        assertFalse(content.contains("kubectl"));
        assertFalse(content.contains("当前值"));
    }
}
