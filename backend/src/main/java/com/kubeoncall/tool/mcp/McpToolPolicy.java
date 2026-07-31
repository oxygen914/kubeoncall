package com.kubeoncall.tool.mcp;

import java.util.List;

import com.kubeoncall.common.config.KubeOnCallProperties;

/** Local enforcement boundary; remote MCP annotations are never treated as authorization. */
final class McpToolPolicy {

    private final KubeOnCallProperties properties;

    McpToolPolicy(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    boolean allowed(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        List<String> patterns = properties.getMcp().getAllowedTools();
        return patterns.stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .anyMatch(pattern -> matches(toolName, pattern.trim()));
    }

    private boolean matches(String toolName, String pattern) {
        if ("*".equals(pattern)) {
            return true;
        }
        return pattern.endsWith(".*")
                ? toolName.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(toolName);
    }
}
