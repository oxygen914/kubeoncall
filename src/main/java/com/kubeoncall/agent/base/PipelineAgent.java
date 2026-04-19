package com.kubeoncall.agent.base;

import com.kubeoncall.agent.node.AgentNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

import java.util.List;

public abstract class PipelineAgent extends AbstractAgent {

    protected abstract List<AgentNode> nodes();

    @Override
    public GraphState run(GraphState state) {
        for (AgentNode node : nodes()) {
            NodeResult result = node.execute(state);
            state.addNodeResult(result);
            if (result == null) {
                break;
            }
            if (result.status() == NodeStatus.FAILURE) {
                state.setStatus(GraphStatus.FAILED);
                return state;
            }
            if (result.status() == NodeStatus.WAITING) {
                state.setStatus(GraphStatus.PAUSED);
                return state;
            }
        }
        state.setStatus(GraphStatus.SUCCESS);
        return state;
    }
}
