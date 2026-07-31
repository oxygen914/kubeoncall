package com.kubeoncall.tool.mcp;

import java.util.List;
import java.util.Map;

public interface McpClient {

    Map<String, Object> call(String toolName, Map<String, Object> payload);

    /** Returns the remote server's tool schema when discovery is enabled. */
    default List<Map<String, Object>> listTools() {
        return List.of();
    }
}
