package com.kubeoncall.agent.planner;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.evidence.EvidenceItem;

/** Publishes collected planner evidence to graph state and builds the node result. */
@Component
public class PlannerToolQueryResultAssembler {

    private static final int MAX_COLLECTED_EVIDENCE = 12;
    private static final int MAX_EVIDENCE_SNIPPET_CHARS = 800;

    private final PlannerContextAssembler contextAssembler;

    public PlannerToolQueryResultAssembler(PlannerContextAssembler contextAssembler) {
        this.contextAssembler = contextAssembler;
    }

    public NodeResult assemble(
            GraphState state,
            String request,
            PlannerToolEvidenceCollector.Evidence evidence,
            PlannerToolCandidates.Selection toolCandidates) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", request);
        payload.put("evidenceTarget", evidence.target());
        payload.put("plannerReadOnlyValidated", toolCandidates.readOnlyValidated());
        payload.putAll(evidence.payload());
        attachCollectedEvidence(payload, state);
        contextAssembler.attachSkillKnowledge(payload, state);
        state.getContext().put("plannerAvailableTools", toolCandidates.tools());
        state.getContext().put("plannerKnowledge", payload);
        state.addObservation("Planner queried read-only tools for target=" + evidence.target() + ", tools="
                + toolCandidates.tools().stream()
                        .map(tool -> String.valueOf(tool.get("name")))
                        .toList());
        return new NodeResult("plannerQueryToolNode", NodeStatus.SUCCESS, "Planner queried knowledge sources", payload);
    }

    private void attachCollectedEvidence(Map<String, Object> payload, GraphState state) {
        Object rawItems = state.getContext().get("evidenceItems");
        if (!(rawItems instanceof java.util.List<?> items)) {
            return;
        }
        java.util.List<Map<String, Object>> summaries = items.stream()
                .filter(EvidenceItem.class::isInstance)
                .map(EvidenceItem.class::cast)
                .limit(MAX_COLLECTED_EVIDENCE)
                .map(this::evidenceSummary)
                .toList();
        if (!summaries.isEmpty()) {
            payload.put("collectedEvidence", summaries);
        }
        Object confidence = state.getContext().get("evidenceConfidence");
        if (confidence != null) {
            payload.put("collectedEvidenceConfidence", confidence);
        }
    }

    private Map<String, Object> evidenceSummary(EvidenceItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", item.evidenceId());
        value.put("type", item.type().name());
        value.put("source", item.source());
        value.put("collectionStatus", item.collectionStatus().name());
        value.put("summary", item.summary());
        value.put("snippet", abbreviate(item.snippet(), MAX_EVIDENCE_SNIPPET_CHARS));
        value.put(
                "resource",
                Map.of(
                        "kind", item.resource().kind(),
                        "name", item.resource().name(),
                        "uid", item.resource().uid()));
        value.put("observedAt", item.observedAt().toString());
        if (!item.errorType().isBlank()) {
            value.put("errorType", item.errorType());
        }
        if (!item.metadata().isEmpty()) {
            value.put("metadata", item.metadata());
        }
        return value;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }
}
