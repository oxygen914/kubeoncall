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
    private final WorkflowTemplateRegistry workflowTemplateRegistry;

    public AlertWorkflowFactory(LogCollectionNode logCollectionNode,
                                DeviceInfoNode deviceInfoNode,
                                StateCompareNode stateCompareNode,
                                KnowledgeRetrieveNode knowledgeRetrieveNode,
                                ResultPushNode resultPushNode,
                                WorkflowTemplateRegistry workflowTemplateRegistry) {
        this.logCollectionNode = logCollectionNode;
        this.deviceInfoNode = deviceInfoNode;
        this.stateCompareNode = stateCompareNode;
        this.knowledgeRetrieveNode = knowledgeRetrieveNode;
        this.resultPushNode = resultPushNode;
        this.workflowTemplateRegistry = workflowTemplateRegistry;
    }

    public List<AlertWorkflowDefinition> buildWorkflow() {
        return buildWorkflow(WorkflowTemplateRegistry.DEFAULT);
    }

    public List<AlertWorkflowDefinition> buildWorkflow(String workflowTemplate) {
        String template = workflowTemplateRegistry.normalize(workflowTemplate);
        return switch (template) {
            case WorkflowTemplateRegistry.K8S_POD -> k8sPodWorkflow();
            case WorkflowTemplateRegistry.K8S_NODE -> k8sNodeWorkflow();
            case WorkflowTemplateRegistry.WORKLOAD -> workloadWorkflow();
            case WorkflowTemplateRegistry.CONTROL_PLANE -> controlPlaneWorkflow();
            case WorkflowTemplateRegistry.HOST_RESOURCE -> hostResourceWorkflow();
            default -> defaultWorkflow();
        };
    }

    private List<AlertWorkflowDefinition> defaultWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("logCollectionNode", true, List.of(), logCollectionNode),
                new AlertWorkflowDefinition("deviceInfoNode", true, List.of("logCollectionNode"), deviceInfoNode),
                new AlertWorkflowDefinition("stateCompareNode", true, List.of("deviceInfoNode"), stateCompareNode),
                new AlertWorkflowDefinition("knowledgeRetrieveNode", true, List.of("logCollectionNode", "stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition("resultPushNode", false, List.of("knowledgeRetrieveNode", "stateCompareNode"), resultPushNode)
        );
    }

    private List<AlertWorkflowDefinition> hostResourceWorkflow() {
        return defaultWorkflow();
    }

    private List<AlertWorkflowDefinition> k8sPodWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("logCollectionNode", true, List.of(), logCollectionNode),
                new AlertWorkflowDefinition("stateCompareNode", true, List.of("logCollectionNode"), stateCompareNode),
                new AlertWorkflowDefinition("knowledgeRetrieveNode", true, List.of("logCollectionNode", "stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition("resultPushNode", false, List.of("knowledgeRetrieveNode", "stateCompareNode"), resultPushNode)
        );
    }

    private List<AlertWorkflowDefinition> k8sNodeWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("deviceInfoNode", true, List.of(), deviceInfoNode),
                new AlertWorkflowDefinition("stateCompareNode", true, List.of("deviceInfoNode"), stateCompareNode),
                new AlertWorkflowDefinition("knowledgeRetrieveNode", true, List.of("stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition("resultPushNode", false, List.of("knowledgeRetrieveNode", "stateCompareNode"), resultPushNode)
        );
    }

    private List<AlertWorkflowDefinition> workloadWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("logCollectionNode", true, List.of(), logCollectionNode),
                new AlertWorkflowDefinition("stateCompareNode", true, List.of("logCollectionNode"), stateCompareNode),
                new AlertWorkflowDefinition("knowledgeRetrieveNode", true, List.of("stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition("resultPushNode", false, List.of("knowledgeRetrieveNode", "stateCompareNode"), resultPushNode)
        );
    }

    private List<AlertWorkflowDefinition> controlPlaneWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("stateCompareNode", true, List.of(), stateCompareNode),
                new AlertWorkflowDefinition("knowledgeRetrieveNode", true, List.of("stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition("resultPushNode", false, List.of("knowledgeRetrieveNode", "stateCompareNode"), resultPushNode)
        );
    }
}
