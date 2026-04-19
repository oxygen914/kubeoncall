package com.kubeoncall.agent.base;

import com.kubeoncall.agent.node.AgentNode;
import com.kubeoncall.common.exception.ReplanRequiredException;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

import java.util.List;

public abstract class ReActAgent extends AbstractAgent {

    protected abstract List<AgentNode> loopNodes();

    protected abstract int maxLoops();

    @Override
    public GraphState run(GraphState state) {
        int loops = 0;
        while (loops < maxLoops()) {
            boolean retry = false;
            for (AgentNode node : loopNodes()) {
                NodeResult result = node.execute(state);
                state.addNodeResult(result);
                if (result.status() == NodeStatus.RETRY) {
                    state.addObservation(result.message());
                    retry = true;
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
            if (!retry) {
                state.setStatus(GraphStatus.SUCCESS);
                return state;
            }
            loops++;
            state.setCurrentLoop(loops);
        }
        state.setStatus(GraphStatus.REPLAN_REQUIRED);
        throw new ReplanRequiredException("Max loop count exceeded for agent " + getAgentName());
    }
}
