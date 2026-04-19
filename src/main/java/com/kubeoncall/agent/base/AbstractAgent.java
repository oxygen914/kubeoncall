package com.kubeoncall.agent.base;

import com.kubeoncall.domain.graph.GraphState;

public abstract class AbstractAgent {

    public abstract String getAgentName();

    public abstract GraphState run(GraphState state);
}
