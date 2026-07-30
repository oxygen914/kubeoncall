package com.kubeoncall.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kubeoncall.workflow.node.DeviceInfoNode;
import com.kubeoncall.workflow.node.IntelligentDiagnosisNode;
import com.kubeoncall.workflow.node.KnowledgeRetrieveNode;
import com.kubeoncall.workflow.node.LogCollectionNode;
import com.kubeoncall.workflow.node.NotificationNode;
import com.kubeoncall.workflow.node.ResultPushNode;
import com.kubeoncall.workflow.node.SilenceNode;
import com.kubeoncall.workflow.node.StateCompareNode;
import com.kubeoncall.workflow.node.TicketNode;

class AlertWorkflowFactoryTest {

    @Test
    void defaultWorkflowShouldKeepLegacyNodeOrder() {
        AlertWorkflowFactory factory = factory();

        List<String> nodeNames = factory.buildWorkflow().stream()
                .map(AlertWorkflowDefinition::name)
                .toList();

        assertIterableEquals(
                List.of(
                        "logCollectionNode",
                        "deviceInfoNode",
                        "stateCompareNode",
                        "knowledgeRetrieveNode",
                        "intelligentDiagnosisNode",
                        "resultPushNode",
                        "notificationNode",
                        "ticketNode",
                        "silenceNode"),
                nodeNames);
    }

    @Test
    void k8sPodWorkflowShouldSkipDeviceNode() {
        AlertWorkflowFactory factory = factory();

        List<String> nodeNames = factory.buildWorkflow("k8s-pod").stream()
                .map(AlertWorkflowDefinition::name)
                .toList();

        assertIterableEquals(
                List.of(
                        "logCollectionNode",
                        "stateCompareNode",
                        "knowledgeRetrieveNode",
                        "intelligentDiagnosisNode",
                        "resultPushNode",
                        "notificationNode",
                        "ticketNode",
                        "silenceNode"),
                nodeNames);
    }

    @Test
    void nodeMvpWorkflowShouldRunKnowledgeAndSkillDiagnosisWithoutBlockingNotification() {
        AlertWorkflowFactory factory = factory();

        List<AlertWorkflowDefinition> workflow = factory.buildWorkflow("node-mvp");
        List<String> nodeNames =
                workflow.stream().map(AlertWorkflowDefinition::name).toList();

        assertIterableEquals(
                List.of(
                        "stateCompareNode",
                        "knowledgeRetrieveNode",
                        "intelligentDiagnosisNode",
                        "resultPushNode",
                        "notificationNode"),
                nodeNames);
        assertIterableEquals(List.of(), workflow.get(1).dependencies());
        assertIterableEquals(List.of(), workflow.get(2).dependencies());
        assertIterableEquals(List.of(), workflow.get(3).dependencies());
        assertIterableEquals(List.of("resultPushNode"), workflow.get(4).dependencies());
    }

    @Test
    void controlPlaneWorkflowShouldStartFromStateCompare() {
        AlertWorkflowFactory factory = factory();

        List<AlertWorkflowDefinition> workflow = factory.buildWorkflow("control-plane");

        assertEquals("stateCompareNode", workflow.get(0).name());
        assertIterableEquals(
                List.of("knowledgeRetrieveNode", "stateCompareNode"),
                workflow.get(2).dependencies());
    }

    @Test
    void unknownTemplateShouldFallbackToDefaultWorkflow() {
        AlertWorkflowFactory factory = factory();

        List<String> fallback = factory.buildWorkflow("unknown-template").stream()
                .map(AlertWorkflowDefinition::name)
                .toList();
        List<String> defaults = factory.buildWorkflow().stream()
                .map(AlertWorkflowDefinition::name)
                .toList();

        assertIterableEquals(defaults, fallback);
    }

    private static AlertWorkflowFactory factory() {
        return new AlertWorkflowFactory(
                mock(LogCollectionNode.class),
                mock(DeviceInfoNode.class),
                mock(StateCompareNode.class),
                mock(KnowledgeRetrieveNode.class),
                mock(IntelligentDiagnosisNode.class),
                mock(ResultPushNode.class),
                mock(NotificationNode.class),
                mock(TicketNode.class),
                mock(SilenceNode.class),
                new WorkflowTemplateRegistry());
    }
}
