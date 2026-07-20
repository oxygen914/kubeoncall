package com.kubeoncall.workflow;

import java.util.List;

public record AlertWorkflowDefinition(
        String name, boolean continueOnFailure, List<String> dependencies, AlertWorkflowNode node) {}
