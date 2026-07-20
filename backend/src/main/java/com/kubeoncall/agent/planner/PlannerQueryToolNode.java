package com.kubeoncall.agent.planner;

import java.util.List;

import org.springframework.stereotype.Component;

import com.kubeoncall.agent.node.QueryToolNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;

/** Coordinates planner tool candidates, read-only evidence collection, and result publication. */
@Component
public class PlannerQueryToolNode extends QueryToolNode {

    private final PlannerContextAssembler contextAssembler;
    private final PlannerToolCandidates toolCandidates;
    private final PlannerToolEvidenceCollector evidenceCollector;
    private final PlannerToolQueryResultAssembler resultAssembler;

    public PlannerQueryToolNode(
            PlannerContextAssembler contextAssembler,
            PlannerToolCandidates toolCandidates,
            PlannerToolEvidenceCollector evidenceCollector,
            PlannerToolQueryResultAssembler resultAssembler) {
        this.contextAssembler = contextAssembler;
        this.toolCandidates = toolCandidates;
        this.evidenceCollector = evidenceCollector;
        this.resultAssembler = resultAssembler;
    }

    @Override
    public String getName() {
        return "plannerQueryToolNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        String request = contextAssembler.planningRequest(state, state.getUserRequest());
        List<String> missingSignals = contextAssembler.missingSignals(state);
        PlannerToolEvidenceCollector.Evidence evidence = evidenceCollector.collect(request, missingSignals);
        return resultAssembler.assemble(state, request, evidence, toolCandidates.select());
    }
}
