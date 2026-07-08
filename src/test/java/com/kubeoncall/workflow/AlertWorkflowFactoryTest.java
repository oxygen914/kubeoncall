package com.kubeoncall.workflow;

import com.kubeoncall.workflow.node.DeviceInfoNode;
import com.kubeoncall.workflow.node.KnowledgeRetrieveNode;
import com.kubeoncall.workflow.node.LogCollectionNode;
import com.kubeoncall.workflow.node.ResultPushNode;
import com.kubeoncall.workflow.node.StateCompareNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.mock;

class AlertWorkflowFactoryTest {

    @Test
    void defaultWorkflowShouldKeepLegacyNodeOrder() {
        AlertWorkflowFactory factory = factory();

        List<String> nodeNames = factory.buildWorkflow().stream()
                .map(AlertWorkflowDefinition::name)
                .toList();

        assertIterableEquals(List.of(
                "logCollectionNode",
                "deviceInfoNode",
                "stateCompareNode",
                "knowledgeRetrieveNode",
                "resultPushNode"
        ), nodeNames);
    }

    @Test
    void k8sPodWorkflowShouldSkipDeviceNode() {
        AlertWorkflowFactory factory = factory();

        List<String> nodeNames = factory.buildWorkflow("k8s-pod").stream()
                .map(AlertWorkflowDefinition::name)
                .toList();

        assertIterableEquals(List.of(
                "logCollectionNode",
                "stateCompareNode",
                "knowledgeRetrieveNode",
                "resultPushNode"
        ), nodeNames);
    }

    @Test
    void controlPlaneWorkflowShouldStartFromStateCompare() {
        AlertWorkflowFactory factory = factory();

        List<AlertWorkflowDefinition> workflow = factory.buildWorkflow("control-plane");

        assertEquals("stateCompareNode", workflow.get(0).name());
        assertIterableEquals(List.of("knowledgeRetrieveNode", "stateCompareNode"), workflow.get(2).dependencies());
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
                mock(ResultPushNode.class),
                new WorkflowTemplateRegistry()
        );
    }
}
