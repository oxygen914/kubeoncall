package com.kubeoncall.tool;

import java.util.List;
import java.util.Map;

public interface ToolExecutor {

    String getExecutorKind();

    List<ToolDefinition> supportedTools();

    Map<String, Object> execute(String action, Map<String, Object> parameters);
}
