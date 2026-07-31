package com.kubeoncall.web.api.v1.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.VerifierCapability;
import com.kubeoncall.web.api.v1.V1Security;

/** Verifies the v1 tool catalog surface enforces {@code tool:read} and maps all tool fields. */
class ToolsControllerTest {

    private AgentToolCatalog catalog;
    private V1Security security;
    private ToolsController controller;

    @BeforeEach
    void setUp() {
        catalog = mock(AgentToolCatalog.class);
        security = mock(V1Security.class);
        when(security.requirePermission(PermissionCode.TOOL_READ)).thenReturn(null);
        controller = new ToolsController(catalog, security);
    }

    @Test
    void allReturnsGroupedCatalogAndPreservesInputSchema() {
        ToolDefinition planner = new ToolDefinition(
                "knowledge.searchSop",
                "knowledge",
                "search SOP",
                true,
                false,
                List.of(),
                List.of("query"),
                List.of("knowledge-base"),
                Map.of("type", "object"));
        ToolDefinition executor = new ToolDefinition(
                "database.cleanData",
                "database",
                "clean data",
                false,
                true,
                List.of(),
                List.of("namespace", "target"),
                List.of("database"));
        when(catalog.plannerTools()).thenReturn(List.of(planner));
        when(catalog.executorTools()).thenReturn(List.of(executor));
        when(catalog.verifierCapabilities())
                .thenReturn(List.of(new VerifierCapability("verifierThinkNode", "policy", "checks")));

        var view = controller.all().data();

        assertThat(view.planner()).hasSize(1);
        assertThat(view.planner().get(0).name()).isEqualTo("knowledge.searchSop");
        assertThat(view.planner().get(0).inputSchema()).containsEntry("type", "object");
        assertThat(view.executor()).hasSize(1);
        assertThat(view.executor().get(0).readOnly()).isFalse();
        assertThat(view.executor().get(0).requiresApproval()).isTrue();
        assertThat(view.executor().get(0).inputSchema()).isNull();
        assertThat(view.verifier()).hasSize(1);
        verify(security).requirePermission(PermissionCode.TOOL_READ);
    }

    @Test
    void plannerEndpointReturnsOnlyPlannerTools() {
        when(catalog.plannerTools())
                .thenReturn(List.of(new ToolDefinition(
                        "alerts.getActiveAlerts",
                        "alerts",
                        "active alerts",
                        true,
                        false,
                        List.of(),
                        List.of("serviceName"),
                        List.of("alertmanager"))));
        assertThat(controller.planner().data()).hasSize(1);
    }

    @Test
    void executorEndpointReturnsOnlyExecutorTools() {
        when(catalog.executorTools())
                .thenReturn(List.of(new ToolDefinition(
                        "kubernetes.describeResource",
                        "kubernetes",
                        "describe",
                        true,
                        false,
                        List.of(),
                        List.of("namespace", "resourceName"),
                        List.of("k8s-api"))));
        assertThat(controller.executor().data()).hasSize(1);
    }

    @Test
    void verifierEndpointReturnsCapabilities() {
        when(catalog.verifierCapabilities())
                .thenReturn(List.of(
                        new VerifierCapability("verifierApprovalNode", "human_approval", "suspends for review")));
        assertThat(controller.verifier().data()).hasSize(1);
    }
}
