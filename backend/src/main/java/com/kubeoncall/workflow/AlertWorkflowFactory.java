package com.kubeoncall.workflow;

import java.util.List;

import org.springframework.stereotype.Component;

import com.kubeoncall.workflow.node.DeviceInfoNode;
import com.kubeoncall.workflow.node.IntelligentDiagnosisNode;
import com.kubeoncall.workflow.node.KnowledgeRetrieveNode;
import com.kubeoncall.workflow.node.LogCollectionNode;
import com.kubeoncall.workflow.node.NotificationNode;
import com.kubeoncall.workflow.node.ResultPushNode;
import com.kubeoncall.workflow.node.SilenceNode;
import com.kubeoncall.workflow.node.StateCompareNode;
import com.kubeoncall.workflow.node.TicketNode;

@Component
public class AlertWorkflowFactory {

    private final LogCollectionNode logCollectionNode;
    private final DeviceInfoNode deviceInfoNode;
    private final StateCompareNode stateCompareNode;
    private final KnowledgeRetrieveNode knowledgeRetrieveNode;
    private final IntelligentDiagnosisNode intelligentDiagnosisNode;
    private final ResultPushNode resultPushNode;
    private final NotificationNode notificationNode;
    private final TicketNode ticketNode;
    private final SilenceNode silenceNode;
    private final WorkflowTemplateRegistry workflowTemplateRegistry;

    public AlertWorkflowFactory(
            LogCollectionNode logCollectionNode,
            DeviceInfoNode deviceInfoNode,
            StateCompareNode stateCompareNode,
            KnowledgeRetrieveNode knowledgeRetrieveNode,
            IntelligentDiagnosisNode intelligentDiagnosisNode,
            ResultPushNode resultPushNode,
            NotificationNode notificationNode,
            TicketNode ticketNode,
            SilenceNode silenceNode,
            WorkflowTemplateRegistry workflowTemplateRegistry) {
        this.logCollectionNode = logCollectionNode;
        this.deviceInfoNode = deviceInfoNode;
        this.stateCompareNode = stateCompareNode;
        this.knowledgeRetrieveNode = knowledgeRetrieveNode;
        this.intelligentDiagnosisNode = intelligentDiagnosisNode;
        this.resultPushNode = resultPushNode;
        this.notificationNode = notificationNode;
        this.ticketNode = ticketNode;
        this.silenceNode = silenceNode;
        this.workflowTemplateRegistry = workflowTemplateRegistry;
    }

    public List<AlertWorkflowDefinition> buildWorkflow() {
        return buildWorkflow(WorkflowTemplateRegistry.DEFAULT);
    }

    public List<AlertWorkflowDefinition> buildWorkflow(String workflowTemplate) {
        String template = workflowTemplateRegistry.normalize(workflowTemplate);
        return switch (template) {
            case WorkflowTemplateRegistry.NODE_MVP -> nodeMvpWorkflow();
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
                new AlertWorkflowDefinition(
                        "knowledgeRetrieveNode",
                        true,
                        List.of("logCollectionNode", "stateCompareNode"),
                        knowledgeRetrieveNode),
                new AlertWorkflowDefinition(
                        "intelligentDiagnosisNode",
                        false,
                        List.of("knowledgeRetrieveNode", "stateCompareNode"),
                        intelligentDiagnosisNode),
                new AlertWorkflowDefinition(
                        "resultPushNode", false, List.of("intelligentDiagnosisNode"), resultPushNode),
                new AlertWorkflowDefinition("notificationNode", true, List.of("resultPushNode"), notificationNode),
                new AlertWorkflowDefinition("ticketNode", true, List.of("resultPushNode"), ticketNode),
                new AlertWorkflowDefinition("silenceNode", true, List.of("resultPushNode"), silenceNode));
    }

    private List<AlertWorkflowDefinition> hostResourceWorkflow() {
        return defaultWorkflow();
    }

    private List<AlertWorkflowDefinition> nodeMvpWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("stateCompareNode", true, List.of(), stateCompareNode),
                new AlertWorkflowDefinition("knowledgeRetrieveNode", true, List.of(), knowledgeRetrieveNode),
                // Diagnosis has no hard evidence dependency so a Prometheus/RAG outage still
                // produces the deterministic Skill/runbook fallback and reaches notification.
                new AlertWorkflowDefinition("intelligentDiagnosisNode", true, List.of(), intelligentDiagnosisNode),
                new AlertWorkflowDefinition("resultPushNode", false, List.of(), resultPushNode),
                new AlertWorkflowDefinition("notificationNode", true, List.of("resultPushNode"), notificationNode));
    }

    private List<AlertWorkflowDefinition> k8sPodWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("logCollectionNode", true, List.of(), logCollectionNode),
                new AlertWorkflowDefinition("stateCompareNode", true, List.of("logCollectionNode"), stateCompareNode),
                new AlertWorkflowDefinition(
                        "knowledgeRetrieveNode",
                        true,
                        List.of("logCollectionNode", "stateCompareNode"),
                        knowledgeRetrieveNode),
                new AlertWorkflowDefinition(
                        "intelligentDiagnosisNode",
                        false,
                        List.of("knowledgeRetrieveNode", "stateCompareNode"),
                        intelligentDiagnosisNode),
                new AlertWorkflowDefinition(
                        "resultPushNode", false, List.of("intelligentDiagnosisNode"), resultPushNode),
                new AlertWorkflowDefinition("notificationNode", true, List.of("resultPushNode"), notificationNode),
                new AlertWorkflowDefinition("ticketNode", true, List.of("resultPushNode"), ticketNode),
                new AlertWorkflowDefinition("silenceNode", true, List.of("resultPushNode"), silenceNode));
    }

    private List<AlertWorkflowDefinition> k8sNodeWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("deviceInfoNode", true, List.of(), deviceInfoNode),
                new AlertWorkflowDefinition("stateCompareNode", true, List.of("deviceInfoNode"), stateCompareNode),
                new AlertWorkflowDefinition(
                        "knowledgeRetrieveNode", true, List.of("stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition(
                        "intelligentDiagnosisNode",
                        false,
                        List.of("knowledgeRetrieveNode", "stateCompareNode"),
                        intelligentDiagnosisNode),
                new AlertWorkflowDefinition(
                        "resultPushNode", false, List.of("intelligentDiagnosisNode"), resultPushNode),
                new AlertWorkflowDefinition("notificationNode", true, List.of("resultPushNode"), notificationNode),
                new AlertWorkflowDefinition("ticketNode", true, List.of("resultPushNode"), ticketNode),
                new AlertWorkflowDefinition("silenceNode", true, List.of("resultPushNode"), silenceNode));
    }

    private List<AlertWorkflowDefinition> workloadWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("logCollectionNode", true, List.of(), logCollectionNode),
                new AlertWorkflowDefinition("stateCompareNode", true, List.of("logCollectionNode"), stateCompareNode),
                new AlertWorkflowDefinition(
                        "knowledgeRetrieveNode", true, List.of("stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition(
                        "intelligentDiagnosisNode",
                        false,
                        List.of("knowledgeRetrieveNode", "stateCompareNode"),
                        intelligentDiagnosisNode),
                new AlertWorkflowDefinition(
                        "resultPushNode", false, List.of("intelligentDiagnosisNode"), resultPushNode),
                new AlertWorkflowDefinition("notificationNode", true, List.of("resultPushNode"), notificationNode),
                new AlertWorkflowDefinition("ticketNode", true, List.of("resultPushNode"), ticketNode),
                new AlertWorkflowDefinition("silenceNode", true, List.of("resultPushNode"), silenceNode));
    }

    private List<AlertWorkflowDefinition> controlPlaneWorkflow() {
        return List.of(
                new AlertWorkflowDefinition("stateCompareNode", true, List.of(), stateCompareNode),
                new AlertWorkflowDefinition(
                        "knowledgeRetrieveNode", true, List.of("stateCompareNode"), knowledgeRetrieveNode),
                new AlertWorkflowDefinition(
                        "intelligentDiagnosisNode",
                        false,
                        List.of("knowledgeRetrieveNode", "stateCompareNode"),
                        intelligentDiagnosisNode),
                new AlertWorkflowDefinition(
                        "resultPushNode", false, List.of("intelligentDiagnosisNode"), resultPushNode),
                new AlertWorkflowDefinition("notificationNode", true, List.of("resultPushNode"), notificationNode),
                new AlertWorkflowDefinition("ticketNode", true, List.of("resultPushNode"), ticketNode),
                new AlertWorkflowDefinition("silenceNode", true, List.of("resultPushNode"), silenceNode));
    }
}
