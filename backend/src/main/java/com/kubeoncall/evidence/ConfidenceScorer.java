package com.kubeoncall.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Deterministic confidence scoring; model self-reported confidence is never trusted directly. */
@Component
public class ConfidenceScorer {

    public ConfidenceAssessment score(
            List<EvidenceItem> evidence, boolean targetCertain, boolean simulation, int conflictCount) {
        List<EvidenceItem> items = evidence == null ? List.of() : evidence;
        List<EvidenceItem> succeeded =
                items.stream().filter(EvidenceItem::succeeded).toList();
        boolean direct = succeeded.stream()
                .anyMatch(item -> item.type() == EvidenceType.RESOURCE_STATE || item.type() == EvidenceType.K8S_EVENT);
        long sourceCount =
                succeeded.stream().map(EvidenceItem::source).distinct().count();
        boolean sop = succeeded.stream().anyMatch(item -> item.type() == EvidenceType.SOP);
        boolean onlyLogs =
                !succeeded.isEmpty() && succeeded.stream().allMatch(item -> item.type() == EvidenceType.POD_LOG);

        double directEvidence = direct ? 1.0 : 0.0;
        double sourceAgreement = Math.min(1.0, sourceCount / 3.0);
        double freshness = succeeded.isEmpty()
                ? 0.0
                : succeeded.stream().mapToDouble(this::freshness).average().orElse(0.0);
        double sopSupport = sop ? 1.0 : 0.0;
        double targetCertainty = targetCertain ? 1.0 : 0.0;
        long missingCount = items.stream().filter(item -> !item.succeeded()).count();
        double missingPenalty = Math.min(0.25, missingCount * 0.05);
        double conflictPenalty = Math.min(0.30, Math.max(0, conflictCount) * 0.15);

        double score = 0.35 * directEvidence
                + 0.20 * sourceAgreement
                + 0.15 * freshness
                + 0.15 * sopSupport
                + 0.15 * targetCertainty
                - missingPenalty
                - conflictPenalty;
        if (!targetCertain || !direct) {
            score = Math.min(score, 0.49);
        }
        if (onlyLogs) {
            score = Math.min(score, 0.59);
        }
        if (conflictCount > 0) {
            score = Math.min(score, 0.49);
        }
        if (simulation) {
            score = Math.min(score, 0.30);
        }
        score = Math.max(0, Math.min(1, score));

        Map<String, Double> basis = new LinkedHashMap<>();
        basis.put("directEvidence", directEvidence);
        basis.put("sourceAgreement", sourceAgreement);
        basis.put("freshness", freshness);
        basis.put("sopSupport", sopSupport);
        basis.put("targetCertainty", targetCertainty);
        basis.put("missingSignalPenalty", missingPenalty);
        basis.put("conflictPenalty", conflictPenalty);
        return new ConfidenceAssessment(score, label(score), basis);
    }

    private double freshness(EvidenceItem item) {
        long seconds = item.freshnessSeconds();
        if (seconds <= 300) {
            return 1.0;
        }
        if (seconds >= 3600) {
            return 0.0;
        }
        return 1.0 - ((seconds - 300.0) / 3300.0);
    }

    private String label(double score) {
        if (score >= 0.80) {
            return "HIGH";
        }
        if (score >= 0.55) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
