package com.kubeoncall.evidence;

import java.util.Map;

public record ConfidenceAssessment(double score, String label, Map<String, Double> basis) {

    public ConfidenceAssessment {
        score = Math.max(0, Math.min(1, score));
        label = label == null ? "LOW" : label;
        basis = basis == null ? Map.of() : Map.copyOf(basis);
    }
}
