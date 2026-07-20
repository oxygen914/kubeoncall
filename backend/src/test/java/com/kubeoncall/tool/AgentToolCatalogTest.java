package com.kubeoncall.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.mcp.McpToolRegistry;

class AgentToolCatalogTest {

    @Test
    void shouldFilterExecutorToolsByWhitelist() {
        AgentToolCatalog catalog = new AgentToolCatalog(List.of(executor()), new McpToolRegistry());

        List<ToolDefinition> tools = catalog.executorTools(List.of("kubernetes.describeResource"));

        assertEquals(1, tools.size());
        assertEquals("kubernetes.describeResource", tools.get(0).name());
        assertNotNull(catalog.findExecutorTool("kubernetes", "describeResource", List.of("kubernetes.*")));
        assertNull(catalog.findExecutorTool("kubernetes", "scaleWorkload", List.of("kubernetes.describeResource")));
    }

    private ToolExecutor executor() {
        return new ToolExecutor() {
            @Override
            public String getExecutorKind() {
                return "kubernetes";
            }

            @Override
            public List<ToolDefinition> supportedTools() {
                return List.of(
                        new ToolDefinition(
                                "kubernetes.describeResource",
                                "kubernetes",
                                "describe resource",
                                true,
                                false,
                                List.of(TaskType.QUERY_METRICS),
                                List.of("resourceType", "resourceName"),
                                List.of("k8s")),
                        new ToolDefinition(
                                "kubernetes.scaleWorkload",
                                "kubernetes",
                                "scale workload",
                                false,
                                true,
                                List.of(TaskType.SCALE_WORKLOAD),
                                List.of("namespace", "replicas"),
                                List.of("k8s")));
            }

            @Override
            public Map<String, Object> execute(String action, Map<String, Object> parameters) {
                return Map.of();
            }
        };
    }
}
