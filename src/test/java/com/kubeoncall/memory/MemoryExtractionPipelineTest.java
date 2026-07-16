package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

class MemoryExtractionPipelineTest {

    @Test
    void shouldPersistStructuredEvidenceAndQualityMetadata() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setExtractionQualityThreshold(0.55d);
        MemoryExtractionPromptBuilder prompts = mock(MemoryExtractionPromptBuilder.class);
        when(prompts.systemPrompt()).thenReturn("system");
        when(prompts.userPrompt(any())).thenReturn("user");
        HttpMemoryStructuredExtractionClient llmClient = mock(HttpMemoryStructuredExtractionClient.class);
        when(llmClient.extract("system", "user"))
                .thenReturn(Optional.of(List.of(new MemoryExtractionCandidate(
                        MemoryType.INCIDENT_SUMMARY,
                        MemoryScope.FINGERPRINT,
                        "payment OOM",
                        "payment worker restarted after memory limit was raised and current metrics were verified",
                        "payment",
                        "pod/payment-1",
                        "fp-1",
                        List.of("memory limit", "metrics"),
                        0.9d))));
        MemoryExtractionPipeline pipeline = new MemoryExtractionPipeline(
                properties, prompts, llmClient, new MemoryQualityScorer(), new TokenBudget());

        MemoryExtractionPipeline.ExtractionResult result = pipeline.extract(task());

        assertEquals("llm_structured", result.mode());
        assertEquals(1, result.entries().size());
        Map<String, String> metadata = result.entries().get(0).metadata();
        assertEquals("verified", metadata.get("evidence_attribution"));
        assertEquals("memory limit | metrics", metadata.get("evidence_references"));
        assertTrue(Double.parseDouble(metadata.get("quality_score")) >= 0.55d);
    }

    @Test
    void shouldRetainHeuristicFallbackWhenLlmIsUnavailable() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        MemoryExtractionPromptBuilder prompts = mock(MemoryExtractionPromptBuilder.class);
        when(prompts.systemPrompt()).thenReturn("system");
        when(prompts.userPrompt(any())).thenReturn("user");
        HttpMemoryStructuredExtractionClient llmClient = mock(HttpMemoryStructuredExtractionClient.class);
        when(llmClient.extract("system", "user")).thenReturn(Optional.empty());
        MemoryExtractionPipeline pipeline = new MemoryExtractionPipeline(
                properties, prompts, llmClient, new MemoryQualityScorer(), new TokenBudget());

        MemoryExtractionPipeline.ExtractionResult result = pipeline.extract(task());

        assertEquals("heuristic_fallback", result.mode());
        assertEquals(1, result.entries().size());
        assertEquals("heuristic_fallback", result.entries().get(0).metadata().get("extraction_mode"));
    }

    private MemoryExtractionTask task() {
        return new MemoryExtractionTask(
                "task-1",
                MemoryType.INCIDENT_SUMMARY,
                MemoryScope.FINGERPRINT,
                "payment OOM",
                "payment worker exceeded memory limit; metrics show a restart count increase",
                "payment",
                "pod/payment-1",
                "fp-1",
                Map.of("source", "alert", "alert_name", "PodOOMKilled"),
                0,
                Instant.parse("2026-07-16T00:00:00Z"));
    }
}
