package com.kubeoncall.agent.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.mcp.McpClient;
import com.kubeoncall.tool.mcp.McpToolRegistry;

class PlannerQueryToolNodeTest {

    @Test
    void shouldAssembleFallbackEvidenceAndSupplementalSignalsIntoGraphState() {
        McpClient mcpClient = mock(McpClient.class);
        when(mcpClient.call(anyString(), anyMap())).thenReturn(Map.of("status", "failed"));
        PlannerQueryToolNode node = node(mcpClient);
        GraphState state = new GraphState();
        state.setUserRequest("生产 payment-service 告警，需要配置 timeout");
        state.getContext()
                .put(
                        "plannerMissingSignals",
                        List.of("target_replica_count_not_specified", "specific_config_key_not_identified"));
        state.getContext().put("activatedSkillIds", List.of("payment-runbook"));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals(true, result.payload().get("plannerReadOnlyValidated"));
        assertEquals(List.of("payment-runbook"), result.payload().get("activatedSkillIds"));
        assertEquals("production", ((Map<?, ?>) result.payload().get("serviceMetadata")).get("environment"));
        assertEquals(3, ((Map<?, ?>) result.payload().get("supplementalSignals")).get("recommendedReplicas"));
        assertEquals("timeout", ((Map<?, ?>) result.payload().get("supplementalSignals")).get("recommendedConfigKey"));
        assertEquals(result.payload(), state.getContext().get("plannerKnowledge"));
        assertEquals(6, ((List<?>) state.getContext().get("plannerAvailableTools")).size());
        assertTrue(state.getObservations().get(0).contains("target=payment-service"));
        verify(mcpClient, times(2)).call(eq("topology.getServiceTopology"), anyMap());
        verify(mcpClient, times(2)).call(eq("knowledge.searchSop"), anyMap());
    }

    @Test
    void shouldUseSuccessfulMcpResponseAndAnnotateItsSourceTool() {
        McpClient mcpClient = mock(McpClient.class);
        when(mcpClient.call(anyString(), anyMap())).thenReturn(Map.of("status", "failed"));
        when(mcpClient.call(eq("topology.getServiceTopology"), anyMap()))
                .thenReturn(Map.of("status", "success", "response", Map.of("upstreams", List.of("edge"))));
        PlannerQueryToolNode node = node(mcpClient);
        GraphState state = new GraphState();
        state.setUserRequest("inspect gateway-service topology");

        NodeResult result = node.execute(state);

        Map<?, ?> topology = (Map<?, ?>) result.payload().get("topology");
        assertEquals(List.of("edge"), topology.get("upstreams"));
        assertEquals("topology.getServiceTopology", topology.get("tool"));
        assertEquals("gateway-service", ((Map<?, ?>) result.payload().get("serviceMetadata")).get("service"));
    }

    private static PlannerQueryToolNode node(McpClient mcpClient) {
        PlannerContextAssembler contextAssembler = new PlannerContextAssembler();
        return new PlannerQueryToolNode(
                contextAssembler,
                new PlannerToolCandidates(new AgentToolCatalog(List.of(), new McpToolRegistry())),
                new PlannerToolEvidenceCollector(mcpClient),
                new PlannerToolQueryResultAssembler(contextAssembler));
    }
}
