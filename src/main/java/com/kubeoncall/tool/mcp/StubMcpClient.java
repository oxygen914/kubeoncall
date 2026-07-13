package com.kubeoncall.tool.mcp;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kubeoncall.mcp", name = "enabled", havingValue = "false")
public class StubMcpClient implements McpClient {

    @Override
    public Map<String, Object> call(String toolName, Map<String, Object> payload) {
        return Map.of(
                "tool", toolName,
                "payload", payload,
                "status", "stubbed");
    }
}
