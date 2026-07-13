package com.kubeoncall.agent.planner;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

/** Publishes collected planner evidence to graph state and builds the node result. */
@Component
public class PlannerToolQueryResultAssembler {

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
        payload.put("plannerReadOnlyValidated", toolCandidates.readOnlyValidated());
        payload.putAll(evidence.payload());
        contextAssembler.attachSkillKnowledge(payload, state);
        state.getContext().put("plannerAvailableTools", toolCandidates.tools());
        state.getContext().put("plannerKnowledge", payload);
        state.addObservation("Planner queried read-only tools for target=" + evidence.target() + ", tools="
                + toolCandidates.tools().stream()
                        .map(tool -> String.valueOf(tool.get("name")))
                        .toList());
        return new NodeResult("plannerQueryToolNode", NodeStatus.SUCCESS, "Planner queried knowledge sources", payload);
    }
}
