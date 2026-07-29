package com.kubeoncall.agent.planner;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.agent.node.QueryToolNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.evidence.EvidenceOrchestrator;

/** Coordinates planner tool candidates, read-only evidence collection, and result publication. */
@Component
public class PlannerQueryToolNode extends QueryToolNode {

    private final PlannerContextAssembler contextAssembler;
    private final PlannerToolCandidates toolCandidates;
    private final PlannerToolEvidenceCollector evidenceCollector;
    private final EvidenceOrchestrator evidenceOrchestrator;
    private final PlannerToolQueryResultAssembler resultAssembler;

    @Autowired
    public PlannerQueryToolNode(
            PlannerContextAssembler contextAssembler,
            PlannerToolCandidates toolCandidates,
            PlannerToolEvidenceCollector evidenceCollector,
            EvidenceOrchestrator evidenceOrchestrator,
            PlannerToolQueryResultAssembler resultAssembler) {
        this.contextAssembler = contextAssembler;
        this.toolCandidates = toolCandidates;
        this.evidenceCollector = evidenceCollector;
        this.evidenceOrchestrator = evidenceOrchestrator;
        this.resultAssembler = resultAssembler;
    }

    public PlannerQueryToolNode(
            PlannerContextAssembler contextAssembler,
            PlannerToolCandidates toolCandidates,
            PlannerToolEvidenceCollector evidenceCollector,
            PlannerToolQueryResultAssembler resultAssembler) {
        this(contextAssembler, toolCandidates, evidenceCollector, null, resultAssembler);
    }

    @Override
    public String getName() {
        return "plannerQueryToolNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        if (state.getExecutionId() == null || state.getExecutionId().isBlank()) {
            state.setExecutionId(UUID.randomUUID().toString());
        }
        String request = contextAssembler.planningRequest(state, state.getUserRequest());
        List<String> missingSignals = contextAssembler.missingSignals(state);
        PlannerToolEvidenceCollector.Evidence evidence = evidenceCollector.collect(request, missingSignals);
        if (evidenceOrchestrator != null) {
            evidenceOrchestrator.collect(state, request, evidence);
        }
        return resultAssembler.assemble(state, request, evidence, toolCandidates.select());
    }
}
