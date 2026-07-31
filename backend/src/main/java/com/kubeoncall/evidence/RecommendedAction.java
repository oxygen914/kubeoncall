package com.kubeoncall.evidence;

import java.util.Map;

public record RecommendedAction(String type, boolean requiresApproval, Map<String, Object> parameters) {

    public RecommendedAction {
        type = type == null ? "" : type;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
