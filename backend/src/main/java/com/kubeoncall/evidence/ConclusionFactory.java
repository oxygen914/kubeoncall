package com.kubeoncall.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.agent.planner.PlannerMode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;

/** Creates UI/audit conclusions from planner facts and program-scored evidence. */
@Component
public class ConclusionFactory {

    private final ConfidenceScorer confidenceScorer;

    public ConclusionFactory(ConfidenceScorer confidenceScorer) {
        this.confidenceScorer = confidenceScorer;
    }

    public AiConclusion create(GraphState state, Task task, String claim) {
        List<EvidenceItem> evidence = evidence(state.getContext().get("evidenceItems"));
        List<Map<String, Object>> conflicts = conflicts(state.getContext().get("evidenceConflicts"));
        boolean targetCertain = evidence.stream()
                .filter(EvidenceItem::succeeded)
                .anyMatch(item -> item.resource().hasUid());
        PlannerMode mode = PlannerMode.runtime(
                String.valueOf(state.getContext().getOrDefault("plannerMode", PlannerMode.UNAVAILABLE.name())));
        ConfidenceAssessment confidence =
                confidenceScorer.score(evidence, targetCertain, mode == PlannerMode.SIMULATION, conflicts.size());
        state.getContext().put("evidenceConfidence", confidence);

        List<String> evidenceRefs = evidence.stream()
                .filter(EvidenceItem::succeeded)
                .map(EvidenceItem::evidenceId)
                .toList();
        List<SopEvidenceReference> sopRefs = sopReferences(task, evidence);
        Map<String, Object> planner = new LinkedHashMap<>();
        planner.put("mode", mode.name());
        planner.put("model", String.valueOf(state.getContext().getOrDefault("plannerModel", "")));
        planner.put("provider", String.valueOf(state.getContext().getOrDefault("plannerProvider", "")));
        planner.put("degraded", Boolean.TRUE.equals(state.getContext().get("plannerDegraded")));
        if (state.getContext().containsKey("plannerDegradedReason")) {
            planner.put("degradedReason", state.getContext().get("plannerDegradedReason"));
        }
        RecommendedAction action = new RecommendedAction(
                task.taskType().name(), !task.taskType().name().startsWith("QUERY"), task.parameters());
        String normalizedClaim = claim == null || claim.isBlank() ? task.description() : claim;
        String conclusionId = "con_"
                + sha256(state.getExecutionId() + "|" + task.taskId() + "|" + normalizedClaim)
                        .substring(0, 32);
        return new AiConclusion(
                conclusionId,
                state.getExecutionId(),
                normalizedClaim,
                severity(task.riskLevel()),
                supportStatus(evidenceRefs, conflicts, confidence),
                evidenceRefs,
                sopRefs,
                confidence,
                planner,
                action);
    }

    private List<SopEvidenceReference> sopReferences(Task task, List<EvidenceItem> evidence) {
        List<SopEvidenceReference> references = new ArrayList<>();
        SopReference taskSop = task.sopReference();
        if (taskSop != null && taskSop.sopId() != null && !taskSop.sopId().isBlank()) {
            references.add(new SopEvidenceReference(taskSop.sopId(), taskSop.version(), taskSop.source(), ""));
        }
        evidence.stream()
                .filter(item -> item.succeeded() && item.type() == EvidenceType.SOP)
                .filter(item -> references.stream()
                        .noneMatch(reference -> reference.sopId().equals(sopId(item))))
                .forEach(item -> references.add(new SopEvidenceReference(
                        sopId(item),
                        String.valueOf(item.metadata().getOrDefault("version", "")),
                        item.source(),
                        String.valueOf(item.metadata().getOrDefault("section", "")))));
        return List.copyOf(references);
    }

    private static String sopId(EvidenceItem item) {
        String value = String.valueOf(item.metadata().getOrDefault("sopId", ""));
        return value.isBlank() ? item.evidenceId() : value;
    }

    private static String supportStatus(
            List<String> evidenceRefs, List<Map<String, Object>> conflicts, ConfidenceAssessment confidence) {
        if (!conflicts.isEmpty()) {
            return "CONFLICTED";
        }
        if (evidenceRefs.isEmpty()) {
            return "UNSUPPORTED";
        }
        return confidence.score() >= 0.55 ? "SUPPORTED" : "PARTIALLY_SUPPORTED";
    }

    private static String severity(RiskLevel riskLevel) {
        if (riskLevel == null) {
            return "P4";
        }
        return switch (riskLevel) {
            case CRITICAL -> "P1";
            case HIGH -> "P2";
            case MEDIUM -> "P3";
            case LOW -> "P4";
        };
    }

    private static List<EvidenceItem> evidence(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(EvidenceItem.class::isInstance)
                .map(EvidenceItem.class::cast)
                .toList();
    }

    private static List<Map<String, Object>> conflicts(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(ConclusionFactory::stringMap)
                .toList();
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
