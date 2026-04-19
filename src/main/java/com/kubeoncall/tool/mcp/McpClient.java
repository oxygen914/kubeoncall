package com.kubeoncall.tool.mcp;

import java.util.Map;

public interface McpClient {

    Map<String, Object> call(String toolName, Map<String, Object> payload);
}
