package com.kubeoncall.agent.executor;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.agent.node.ExecuteNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

@Component
public class OperationClosureNode extends ExecuteNode {

    private final OperationClosureService closureService;

    public OperationClosureNode(OperationClosureService closureService) {
        this.closureService = closureService;
    }

    @Override
    public String getName() {
        return "operationClosureNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        OperationClosureService.Outcome outcome = closureService.close(state);
        return new NodeResult(
                getName(),
                outcome.successful() ? NodeStatus.SUCCESS : NodeStatus.FAILURE,
                outcome.message(),
                outcome.details().isEmpty() ? Map.of("closureStatus", outcome.status()) : outcome.details());
    }
}
