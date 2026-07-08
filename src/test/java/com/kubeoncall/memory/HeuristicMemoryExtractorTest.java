package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HeuristicMemoryExtractorTest {

    @Test
    void shouldExtractKnownPitfallFromAskAnswer() {
        MemoryService memoryService = mock(MemoryService.class);
        HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor(memoryService, new KubeOnCallProperties());
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

        ArgumentCaptor<MemoryEntry> entryCaptor = ArgumentCaptor.forClass(MemoryEntry.class);
        verify(memoryService).remember(entryCaptor.capture());
        assertEquals(MemoryType.KNOWN_PITFALL, entryCaptor.getValue().type());
        assertEquals("payment-service", entryCaptor.getValue().service());
    }

    @Test
    void shouldSkipKubectlAndRealtimeContent() {
        MemoryService memoryService = mock(MemoryService.class);
        HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor(memoryService, new KubeOnCallProperties());

        extractor.extractFromAsk(new GraphState(), "kubectl get pods -n prod shows current value 3");

        verify(memoryService, never()).remember(org.mockito.ArgumentMatchers.any());
    }
}
