package com.kubeoncall.task.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryExtractionPipeline;
import com.kubeoncall.memory.MemoryExtractionTaskHandler;
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryService;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.memory.mysql.MemoryEntryRecord;
import com.kubeoncall.memory.mysql.MemoryEntryRepository;
import com.kubeoncall.memory.mysql.MemoryEntryRepository.UpsertMemory;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRecord;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;

class MemoryExtractionTaskHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-21T04:00:00Z");

    @Test
    void extractsPersistsAndCompletesGovernanceProjectionUnderTaskFence() {
        MemoryExtractionPipeline pipeline = mock(MemoryExtractionPipeline.class);
        MemoryService memoryService = mock(MemoryService.class);
        MemoryEntryRepository memoryRepository = mock(MemoryEntryRepository.class);
        MemoryExtractionTaskRepository extractionRepository = mock(MemoryExtractionTaskRepository.class);
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        MemoryExtractionTaskRecord pending = extraction("PENDING", 1L);
        MemoryExtractionTaskRecord running = extraction("RUNNING", 2L);
        MemoryEntry entry = memory();
        MemoryEntryRecord fact = fact();
        when(extractionRepository.find("mext_123")).thenReturn(Optional.of(pending), Optional.of(running));
        when(extractionRepository.updateProgress(anyString(), anyLong(), anyInt(), anyInt(), any()))
                .thenReturn(true);
        when(pipeline.extract(any()))
                .thenReturn(new MemoryExtractionPipeline.ExtractionResult(List.of(entry), "llm_structured", 0));
        when(memoryService.remember(entry)).thenReturn(entry);
        when(memoryRepository.upsert(any())).thenReturn(fact);
        when(taskRepository.updateProgress(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(true);
        when(extractionRepository.complete(anyString(), anyLong(), anyInt(), anyInt(), any(), any()))
                .thenReturn(true);
        MemoryExtractionTaskHandler handler = new MemoryExtractionTaskHandler(
                pipeline, memoryService, memoryRepository, extractionRepository, taskRepository);
        AsyncTaskContext context = new AsyncTaskContext(task(), "worker-a", () -> true);

        AsyncTaskHandler.HandlerResult result = handler.handle(context);

        assertThat(result.result())
                .containsEntry("extractionId", "mext_123")
                .containsEntry("memoryCount", 1)
                .containsEntry("mode", "llm_structured");
        ArgumentCaptor<UpsertMemory> factCaptor = ArgumentCaptor.forClass(UpsertMemory.class);
        verify(memoryRepository).upsert(factCaptor.capture());
        assertThat(factCaptor.getValue().sourceExecutionPublicId()).isEqualTo("wfe_123");
        assertThat(factCaptor.getValue().evidence()).containsEntry("content", "restart kubelet");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(extractionRepository).complete(eq("mext_123"), eq(2L), eq(1), eq(1), summaryCaptor.capture(), any());
        assertThat(summaryCaptor.getValue())
                .containsEntry("mode", "llm_structured")
                .containsEntry("discardedCount", 0)
                .containsEntry("persistedCount", 1)
                .containsEntry("averageQuality", new BigDecimal("0.90000"));
    }

    private static AsyncTaskRecord task() {
        return new AsyncTaskRecord(
                1L,
                "tsk_123",
                "MEMORY_EXTRACTION",
                "memory_extraction",
                "mext_123",
                "EXECUTION:wfe_123:v1",
                "RUNNING",
                "queued",
                0,
                Map.of(
                        "sourceType",
                        "EXECUTION",
                        "sourcePublicId",
                        "wfe_123",
                        "memoryType",
                        "INCIDENT_SUMMARY",
                        "scope",
                        "GLOBAL",
                        "subject",
                        "Node recovery",
                        "content",
                        "restart kubelet",
                        "metadata",
                        Map.of("source", "workflow")),
                Map.of(),
                null,
                null,
                "worker-a",
                NOW.plusSeconds(300),
                2L,
                1,
                5,
                NOW,
                NOW,
                null,
                "req_123",
                "trace_123",
                2L,
                NOW.minusSeconds(30),
                NOW);
    }

    private static MemoryExtractionTaskRecord extraction(String status, long version) {
        return new MemoryExtractionTaskRecord(
                "mext_123",
                "tsk_123",
                "EXECUTION",
                "wfe_123",
                "v1",
                status,
                "qwen-plus",
                "v1",
                "RUNNING".equals(status) ? 1 : 0,
                0,
                Map.of(),
                null,
                null,
                "RUNNING".equals(status) ? NOW : null,
                null,
                version,
                NOW.minusSeconds(30),
                NOW);
    }

    private static MemoryEntry memory() {
        return new MemoryEntry(
                "memory-extraction-mext_123-0",
                MemoryType.INCIDENT_SUMMARY,
                MemoryScope.GLOBAL,
                "Node recovery",
                "restart kubelet",
                "kubernetes",
                "node-a",
                "fingerprint-1",
                NOW,
                NOW,
                Map.of("quality_score", "0.90", "evidence_attribution", "verified"));
    }

    private static MemoryEntryRecord fact() {
        return new MemoryEntryRecord(
                "mem_123",
                "INCIDENT_SUMMARY",
                "ACTIVE",
                null,
                "wfe_123",
                null,
                Map.of("content", "restart kubelet"),
                new BigDecimal("0.90000"),
                "0".repeat(64),
                null,
                "memory-extraction-mext_123-0",
                NOW,
                null,
                1L,
                NOW,
                NOW,
                null,
                null);
    }
}
