package com.kubeoncall.agent.executor;

import java.util.List;

import org.springframework.stereotype.Component;

import com.kubeoncall.agent.base.ReActAgent;
import com.kubeoncall.agent.node.AgentNode;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Component
public class ExecutorAgent extends ReActAgent {

    private final ExecutorThinkNode executorThinkNode;
    private final ExecutorExecuteNode executorExecuteNode;
    private final OperationClosureNode operationClosureNode;
    private final KubeOnCallProperties properties;

    public ExecutorAgent(
            ExecutorThinkNode executorThinkNode,
            ExecutorExecuteNode executorExecuteNode,
            OperationClosureNode operationClosureNode,
            KubeOnCallProperties properties) {
        this.executorThinkNode = executorThinkNode;
        this.executorExecuteNode = executorExecuteNode;
        this.operationClosureNode = operationClosureNode;
        this.properties = properties;
    }

    @Override
    public String getAgentName() {
        return "executorAgent";
    }

    @Override
    protected List<AgentNode> loopNodes() {
        return List.of(executorThinkNode, executorExecuteNode);
    }

    public com.kubeoncall.domain.graph.GraphState plan(com.kubeoncall.domain.graph.GraphState state) {
        return runLoop(state, List.of(executorThinkNode), getAgentName() + "Plan");
    }

    public com.kubeoncall.domain.graph.GraphState executePrepared(com.kubeoncall.domain.graph.GraphState state) {
        return runLoop(state, List.of(executorExecuteNode), getAgentName() + "Execute");
    }

    public com.kubeoncall.domain.graph.GraphState closePrepared(com.kubeoncall.domain.graph.GraphState state) {
        return runLoop(state, List.of(operationClosureNode), getAgentName() + "Closure");
    }

    @Override
    protected int maxLoops() {
        return properties.getAgent().getMaxLoops();
    }
}
