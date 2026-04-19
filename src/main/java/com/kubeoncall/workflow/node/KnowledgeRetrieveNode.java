package com.kubeoncall.workflow.node;

import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeRetrieveNode implements AlertWorkflowNode {

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        String query = context.getAlarmEvent().summary();
        List<String> hints = List.of(
                "检查最近变更记录",
                "确认节点资源使用情况",
                "回看对应 SOP 处置步骤"
        );
        context.putAttribute("knowledgeHints", hints);
        return new NodeResult(
                "knowledgeRetrieveNode",
                NodeStatus.SUCCESS,
                "Retrieved related SOP knowledge",
                Map.of(
                        "query", query,
                        "hints", hints
                )
        );
    }
}
