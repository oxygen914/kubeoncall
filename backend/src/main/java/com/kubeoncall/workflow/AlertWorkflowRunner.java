package com.kubeoncall.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

@Service
public class AlertWorkflowRunner {

    private final AlertWorkflowFactory workflowFactory;
    private final WorkflowNodeExecutor nodeExecutor;
    private final KubeOnCallProperties properties;

    public AlertWorkflowRunner(
            AlertWorkflowFactory workflowFactory, WorkflowNodeExecutor nodeExecutor, KubeOnCallProperties properties) {
        this.workflowFactory = workflowFactory;
        this.nodeExecutor = nodeExecutor;
        this.properties = properties;
    }

    public void run(AlertWorkflowContext context, AlarmEvaluationResult evaluation) {
        Duration nodeTimeout = Duration.ofMillis(properties.getWorkflow().getNodeTimeoutMillis());
        List<AlertWorkflowDefinition> workflow = evaluation.workflowTemplate() == null
                ? workflowFactory.buildWorkflow()
                : workflowFactory.buildWorkflow(evaluation.workflowTemplate());
        for (AlertWorkflowDefinition definition : workflow) {
            if (context.isTerminated()) {
                return;
            }
            List<String> missingDependencies = definition.dependencies().stream()
                    .filter(dependency -> !context.getCompletedNodes().contains(dependency))
                    .toList();
            if (!missingDependencies.isEmpty()) {
                context.setDegraded(true);
                context.addSkippedNode(definition.name());
                context.addNodeResult(new NodeResult(
                        definition.name(),
                        NodeStatus.FAILURE,
                        "Skipped due to unmet dependencies: " + String.join(", ", missingDependencies),
                        Map.of(
                                "skipped", true,
                                "dependencies", definition.dependencies(),
                                "missingDependencies", missingDependencies,
                                "failedNodes", context.getFailedNodes(),
                                "completedNodes", context.getCompletedNodes())));
                continue;
            }
            nodeExecutor.execute(definition, context, nodeTimeout);
        }
    }
}
