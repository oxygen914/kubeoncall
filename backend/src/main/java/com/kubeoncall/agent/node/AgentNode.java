package com.kubeoncall.agent.node;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;

public interface AgentNode {

    String getName();

    NodeResult execute(GraphState state);
}
