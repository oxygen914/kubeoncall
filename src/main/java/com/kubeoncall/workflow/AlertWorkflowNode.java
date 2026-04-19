package com.kubeoncall.workflow;

import com.kubeoncall.domain.graph.NodeResult;

@FunctionalInterface
public interface AlertWorkflowNode {

    NodeResult execute(AlertWorkflowContext context);
}
