package com.kubeoncall.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class MemoryQualityScorer {

    public QualityScore score(MemoryExtractionCandidate candidate, MemoryExtractionTask task) {
        if (candidate == null
                || candidate.content() == null
                || candidate.content().isBlank()) {
            return new QualityScore(0d, List.of("empty_content"));
        }
        String content = candidate.content().trim();
        String normalized = content.toLowerCase(Locale.ROOT);
        double score = 0.2d;
        List<String> reasons = new ArrayList<>();
        if (content.length() >= 24) {
            score += 0.2d;
            reasons.add("sufficient_detail");
        }
        if (candidate.subject() != null && !candidate.subject().isBlank()) {
            score += 0.1d;
            reasons.add("has_subject");
        }
        if (candidate.evidence() != null && !candidate.evidence().isEmpty()) {
            score += verifiedEvidence(candidate.evidence(), task.content()) ? 0.25d : 0.05d;
            reasons.add(
                    verifiedEvidence(candidate.evidence(), task.content())
                            ? "verified_evidence"
                            : "unverified_evidence");
        }
        if (candidate.confidence() != null) {
            score += Math.max(0d, Math.min(1d, candidate.confidence())) * 0.15d;
        }
        if (isVolatile(normalized)) {
            score -= 0.45d;
            reasons.add("volatile_or_mutating_content");
        }
        if (task.metadata().containsKey("execution_id") || task.metadata().containsKey("alert_name")) {
            score += 0.1d;
            reasons.add("traceable_source");
        }
        if (task.metadata().containsKey("source")) {
            score += 0.15d;
            reasons.add("declared_source");
        }
        return new QualityScore(Math.max(0d, Math.min(1d, score)), List.copyOf(reasons));
    }

    private boolean verifiedEvidence(List<String> evidence, String source) {
        String normalizedSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        return evidence.stream()
                .anyMatch(item -> item != null
                        && !item.isBlank()
                        && normalizedSource.contains(item.trim().toLowerCase(Locale.ROOT)));
    }

    private boolean isVolatile(String content) {
        return content.contains("kubectl ")
                || content.contains("current value")
                || content.contains("当前值")
                || content.contains("实时")
                || content.contains("password")
                || content.contains("token=");
    }

    public record QualityScore(double value, List<String> reasons) {}
}
