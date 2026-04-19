package com.kubeoncall.workflow.node;

import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LogCollectionNode implements AlertWorkflowNode {

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        context.putAttribute("logQuery", context.getAlarmEvent().summary());
        return new NodeResult(
                "logCollectionNode",
                NodeStatus.SUCCESS,
                "Collected logs for alarm",
                Map.of(
                        "alarmId", context.getAlarmEvent().alarmId(),
                        "query", context.getAlarmEvent().summary()
                )
        );
    }
}
