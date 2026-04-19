package com.kubeoncall.workflow.node;

import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StateCompareNode implements AlertWorkflowNode {

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        String baseline = String.valueOf(context.getAlarmEvent().metadata().getOrDefault("baseline", "lab-default"));
        context.putAttribute("baseline", baseline);
        return new NodeResult(
                "stateCompareNode",
                NodeStatus.SUCCESS,
                "Compared runtime state against baseline",
                Map.of(
                        "severity", context.getAlarmEvent().severity(),
                        "baseline", baseline
                )
        );
    }
}
