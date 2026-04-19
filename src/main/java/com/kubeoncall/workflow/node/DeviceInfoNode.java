package com.kubeoncall.workflow.node;

import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DeviceInfoNode implements AlertWorkflowNode {

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        context.putAttribute("targetNode", context.getAlarmEvent().nodeName());
        return new NodeResult(
                "deviceInfoNode",
                NodeStatus.SUCCESS,
                "Fetched device info",
                Map.of(
                        "nodeName", context.getAlarmEvent().nodeName(),
                        "severity", context.getAlarmEvent().severity()
                )
        );
    }
}
