package com.kubeoncall.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

/** Turns queued raw evidence into traceable, quality-scored memory entries. */
@Component
public class MemoryExtractionPipeline {

    private final KubeOnCallProperties properties;
    private final MemoryExtractionPromptBuilder promptBuilder;
    private final HttpMemoryStructuredExtractionClient llmClient;
    private final MemoryQualityScorer qualityScorer;
    private final TokenBudget tokenBudget;

    public MemoryExtractionPipeline(
            KubeOnCallProperties properties,
            MemoryExtractionPromptBuilder promptBuilder,
            HttpMemoryStructuredExtractionClient llmClient,
            MemoryQualityScorer qualityScorer,
            TokenBudget tokenBudget) {
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.qualityScorer = qualityScorer;
        this.tokenBudget = tokenBudget;
    }

    public ExtractionResult extract(MemoryExtractionTask task) {
        Optional<List<MemoryExtractionCandidate>> llmCandidates =
                llmClient.extract(promptBuilder.systemPrompt(), promptBuilder.userPrompt(task));
        String mode = llmCandidates.isPresent() ? "llm_structured" : "heuristic_fallback";
        List<MemoryExtractionCandidate> candidates = llmCandidates.orElseGet(() -> List.of(fallback(task)));
        List<MemoryEntry> entries = new ArrayList<>();
        int discarded = 0;
        for (int index = 0; index < candidates.size(); index++) {
            MemoryExtractionCandidate candidate = candidates.get(index);
            MemoryQualityScorer.QualityScore quality = qualityScorer.score(candidate, task);
            if (quality.value() < properties.getMemory().getExtractionQualityThreshold()) {
                discarded++;
                continue;
            }
            entries.add(toMemoryEntry(task, candidate, quality, mode, index));
        }
        return new ExtractionResult(List.copyOf(entries), mode, discarded);
    }

    private MemoryExtractionCandidate fallback(MemoryExtractionTask task) {
        return new MemoryExtractionCandidate(
                task.memoryType(),
                task.scope(),
                task.subject(),
                task.content(),
                task.service(),
                task.resource(),
                task.fingerprint(),
                List.of(),
                null);
    }

    private MemoryEntry toMemoryEntry(
            MemoryExtractionTask task,
            MemoryExtractionCandidate candidate,
            MemoryQualityScorer.QualityScore quality,
            String mode,
            int candidateIndex) {
        Map<String, String> metadata = new LinkedHashMap<>(task.metadata());
        metadata.put("extraction_mode", mode);
        metadata.put("extraction_task_id", task.id());
        metadata.put("extraction_candidate_index", String.valueOf(candidateIndex));
        metadata.put("quality_score", String.format(java.util.Locale.ROOT, "%.2f", quality.value()));
        metadata.put("quality_reasons", String.join(",", quality.reasons()));
        metadata.put("evidence_source", task.metadata().getOrDefault("source", "unknown"));
        metadata.put("evidence_references", String.join(" | ", candidate.evidence()));
        metadata.put(
                "evidence_attribution",
                hasVerifiedEvidence(candidate.evidence(), task.content()) ? "verified" : "source_linked");
        metadata.put("evidence_excerpt", tokenBudget.compactText(task.content(), 80));
        if (candidate.confidence() != null) {
            metadata.put("llm_confidence", String.format(java.util.Locale.ROOT, "%.2f", candidate.confidence()));
        }
        Instant now = Instant.now();
        return new MemoryEntry(
                deterministicMemoryId(task.id(), candidateIndex),
                candidate.memoryType(),
                candidate.scope(),
                firstNonBlank(candidate.subject(), task.subject()),
                candidate.content().trim(),
                firstNonBlank(candidate.service(), task.service()),
                firstNonBlank(candidate.resource(), task.resource()),
                firstNonBlank(candidate.fingerprint(), task.fingerprint()),
                task.createdAt(),
                now,
                metadata);
    }

    private String deterministicMemoryId(String taskId, int candidateIndex) {
        String normalizedTaskId = taskId == null || taskId.isBlank() ? "unknown" : taskId.trim();
        return "memory-extraction-" + normalizedTaskId + "-" + candidateIndex;
    }

    private boolean hasVerifiedEvidence(List<String> evidence, String source) {
        if (evidence == null || evidence.isEmpty() || source == null) {
            return false;
        }
        String normalizedSource = source.toLowerCase(java.util.Locale.ROOT);
        return evidence.stream()
                .anyMatch(item -> item != null
                        && !item.isBlank()
                        && normalizedSource.contains(item.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    public record ExtractionResult(List<MemoryEntry> entries, String mode, int discarded) {}
}
