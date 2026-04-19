package com.kubeoncall.workflow;

import com.kubeoncall.workflow.node.DeviceInfoNode;
import com.kubeoncall.workflow.node.KnowledgeRetrieveNode;
import com.kubeoncall.workflow.node.LogCollectionNode;
import com.kubeoncall.workflow.node.ResultPushNode;
import com.kubeoncall.workflow.node.StateCompareNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AlertWorkflowFactory {

    private final LogCollectionNode logCollectionNode;
    private final DeviceInfoNode deviceInfoNode;
    private final StateCompareNode stateCompareNode;
    private final KnowledgeRetrieveNode knowledgeRetrieveNode;
    private final ResultPushNode resultPushNode;

    public AlertWorkflowFactory(LogCollectionNode logCollectionNode,
                                DeviceInfoNode deviceInfoNode,
                                StateCompareNode stateCompareNode,
                                KnowledgeRetrieveNode knowledgeRetrieveNode,
                                ResultPushNode resultPushNode) {
        this.logCollectionNode = logCollectionNode;
        this.deviceInfoNode = deviceInfoNode;
        this.stateCompareNode = stateCompareNode;
        this.knowledgeRetrieveNode = knowledgeRetrieveNode;
        this.resultPushNode = resultPushNode;
    }

    public List<AlertWorkflowDefinition> buildWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("logCollectionNode", true, logCollectionNode),
                new AlertWorkflowDefinition("deviceInfoNode", true, deviceInfoNode),
                new AlertWorkflowDefinition("stateCompareNode", true, stateCompareNode),
                new AlertWorkflowDefinition("knowledgeRetrieveNode", true, knowledgeRetrieveNode),
                new AlertWorkflowDefinition("resultPushNode", false, resultPushNode)
        );
    }
}
