package com.kubeoncall.agent.planner;

import com.kubeoncall.agent.base.ReActAgent;
import com.kubeoncall.agent.node.AgentNode;
import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlannerAgent extends ReActAgent {

    private final PlannerThinkNode plannerThinkNode;
    private final PlannerQueryToolNode plannerQueryToolNode;
    private final KubeOnCallProperties properties;

    public PlannerAgent(PlannerThinkNode plannerThinkNode,
                        PlannerQueryToolNode plannerQueryToolNode,
                        KubeOnCallProperties properties) {
        this.plannerThinkNode = plannerThinkNode;
        this.plannerQueryToolNode = plannerQueryToolNode;
        this.properties = properties;
    }

    @Override
    public String getAgentName() {
        return "plannerAgent";
    }

    @Override
    protected List<AgentNode> loopNodes() {
        return List.of(plannerQueryToolNode, plannerThinkNode);
    }

    @Override
    protected int maxLoops() {
        return properties.getAgent().getMaxLoops();
    }
}
