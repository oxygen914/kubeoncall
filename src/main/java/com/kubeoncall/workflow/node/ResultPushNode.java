package com.kubeoncall.workflow.node;

import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ResultPushNode implements AlertWorkflowNode {

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", context.getAlarmEvent().source());
        summary.put("nodeName", context.getAlarmEvent().nodeName());
        summary.put("degraded", context.isDegraded());
        summary.put("failedNodes", context.getFailedNodes());
        summary.put("steps", context.getNodeResults().stream().map(result -> result.nodeName() + ":" + result.status()).toList());
        context.putAttribute("resultSummary", summary);
        return new NodeResult(
                "resultPushNode",
                NodeStatus.SUCCESS,
                context.isDegraded() ? "Pushed degraded workflow result" : "Pushed workflow result",
                summary
        );
    }
}
