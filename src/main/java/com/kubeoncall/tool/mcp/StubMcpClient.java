package com.kubeoncall.tool.mcp;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StubMcpClient implements McpClient {

    @Override
    public Map<String, Object> call(String toolName, Map<String, Object> payload) {
        return Map.of(
                "tool", toolName,
                "payload", payload,
                "status", "stubbed"
        );
    }
}
