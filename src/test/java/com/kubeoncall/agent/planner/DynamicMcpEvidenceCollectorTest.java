package com.kubeoncall.agent.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.mcp.McpClient;
import com.kubeoncall.tool.mcp.McpToolRegistry;

class DynamicMcpEvidenceCollectorTest {

    @Test
    void shouldSelectAndInvokeDiscoveredReadOnlyTool() {
        KubeOnCallProperties properties = properties();
        McpClient client = mock(McpClient.class);
        when(client.listTools())
                .thenReturn(List.of(Map.of(
                        "name",
                        "inventory.lookupOwner",
                        "description",
                        "Lookup service ownership metadata",
                        "readOnly",
                        true,
                        "requiredParameters",
                        List.of("serviceName"))));
        when(client.call(eq("inventory.lookupOwner"), anyMap()))
                .thenReturn(Map.of("status", "success", "response", Map.of("owner", "payments-platform")));
        DynamicMcpEvidenceCollector collector =
                new DynamicMcpEvidenceCollector(client, new McpToolRegistry(client, properties), properties);

        DynamicMcpEvidenceCollector.Result result =
                collector.collect("who owns payment-service", "payment-service", "prod", List.of());

        assertEquals(1, result.invocations().size());
        assertEquals("inventory.lookupOwner", result.invocations().get(0).get("tool"));
        assertEquals(
                Map.of("serviceName", "payment-service"),
                result.invocations().get(0).get("parameters"));
        assertEquals(
                Map.of("owner", "payments-platform"),
                result.invocations().get(0).get("response"));
    }

    @Test
    void shouldSkipToolWhenRequiredParameterCannotBeResolved() {
        KubeOnCallProperties properties = properties();
        McpClient client = mock(McpClient.class);
        when(client.listTools())
                .thenReturn(List.of(Map.of(
                        "name",
                        "inventory.lookupOwner",
                        "description",
                        "Lookup service owner by account id",
                        "readOnly",
                        true,
                        "requiredParameters",
                        List.of("accountId"))));
        DynamicMcpEvidenceCollector collector =
                new DynamicMcpEvidenceCollector(client, new McpToolRegistry(client, properties), properties);

        DynamicMcpEvidenceCollector.Result result =
                collector.collect("who owns payment-service", "payment-service", "prod", List.of());

        assertTrue(result.invocations().isEmpty());
        assertEquals(List.of("accountId"), result.skipped().get(0).get("missingParameters"));
        verify(client, never()).call(eq("inventory.lookupOwner"), anyMap());
    }

    private KubeOnCallProperties properties() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMcp().setDiscoveryEnabled(true);
        properties.getMcp().setDynamicInvocationEnabled(true);
        properties.getMcp().setDynamicMaxTools(2);
        return properties;
    }
}
