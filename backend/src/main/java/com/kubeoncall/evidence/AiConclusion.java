package com.kubeoncall.evidence;

import java.util.List;
import java.util.Map;

/** Structured operational conclusion with evidence/SOP references and deterministic confidence. */
public record AiConclusion(
        String conclusionId,
        String executionId,
        String claim,
        String severity,
        String status,
        List<String> evidenceRefs,
        List<SopEvidenceReference> sopRefs,
        ConfidenceAssessment confidence,
        Map<String, Object> planner,
        RecommendedAction recommendedAction) {

    public AiConclusion {
        conclusionId = safe(conclusionId);
        executionId = safe(executionId);
        claim = safe(claim);
        severity = safe(severity);
        status = safe(status);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        sopRefs = sopRefs == null ? List.of() : List.copyOf(sopRefs);
        planner = planner == null ? Map.of() : Map.copyOf(planner);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
