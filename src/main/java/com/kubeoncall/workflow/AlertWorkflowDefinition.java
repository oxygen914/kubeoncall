package com.kubeoncall.workflow;

public record AlertWorkflowDefinition(
        String name,
        boolean continueOnFailure,
        AlertWorkflowNode node
) {
}
