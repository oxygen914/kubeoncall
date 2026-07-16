package com.kubeoncall.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

class McpToolRegistryTest {

    @Test
    void shouldUseDiscoveredReadOnlyToolsAndRejectMutatingSchemas() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMcp().setDiscoveryEnabled(true);
        McpClient client = mock(McpClient.class);
        when(client.listTools())
                .thenReturn(List.of(
                        Map.of(
                                "name",
                                "metrics.query",
                                "description",
                                "query metrics",
                                "readOnly",
                                true,
                                "supportedTaskTypes",
                                List.of("QUERY_METRICS"),
                                "requiredParameters",
                                List.of("query")),
                        Map.of("name", "cluster.restart", "readOnly", false)));
        McpToolRegistry registry = new McpToolRegistry(client, properties);

        assertTrue(registry.listPlannerTools().stream().anyMatch(tool -> "metrics.query".equals(tool.name())));
        assertTrue(registry.listPlannerTools().stream().allMatch(tool -> tool.readOnly()));
    }
}
