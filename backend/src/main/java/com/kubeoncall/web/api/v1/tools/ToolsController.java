package com.kubeoncall.web.api.v1.tools;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.VerifierCapability;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/tools} — read-only tool catalog (WBS-1 GAP-01-01). Requires {@code tool:read}. Surfaces
 * the planner/executor/verifier tools the agent runtime can invoke, including the {@code inputSchema}
 * the legacy {@code /api/tools} response dropped, so the Console can show tool purpose, risk
 * (readOnly/requiresApproval), parameters and target dependencies. There is no runtime health probe:
 * a tool's availability is its executor bean being registered (and, for MCP planner tools, the
 * discovery snapshot in {@link AgentToolCatalog}).
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolsController {

    private final AgentToolCatalog agentToolCatalog;
    private final V1Security security;

    public ToolsController(AgentToolCatalog agentToolCatalog, V1Security security) {
        this.agentToolCatalog = agentToolCatalog;
        this.security = security;
    }

    @GetMapping
    public ApiResponse<ToolCatalogView> all() {
        security.requirePermission(PermissionCode.TOOL_READ);
        ToolCatalogView view = new ToolCatalogView(
                agentToolCatalog.plannerTools().stream()
                        .map(ToolsController::toView)
                        .toList(),
                agentToolCatalog.executorTools().stream()
                        .map(ToolsController::toView)
                        .toList(),
                agentToolCatalog.verifierCapabilities());
        return ApiResponse.ok(view, RequestIdFilter.currentRequestId());
    }

    @GetMapping("/planner")
    public ApiResponse<List<ToolView>> planner() {
        security.requirePermission(PermissionCode.TOOL_READ);
        return ApiResponse.ok(
                agentToolCatalog.plannerTools().stream()
                        .map(ToolsController::toView)
                        .toList(),
                RequestIdFilter.currentRequestId());
    }

    @GetMapping("/executor")
    public ApiResponse<List<ToolView>> executor() {
        security.requirePermission(PermissionCode.TOOL_READ);
        return ApiResponse.ok(
                agentToolCatalog.executorTools().stream()
                        .map(ToolsController::toView)
                        .toList(),
                RequestIdFilter.currentRequestId());
    }

    @GetMapping("/verifier")
    public ApiResponse<List<VerifierCapability>> verifier() {
        security.requirePermission(PermissionCode.TOOL_READ);
        return ApiResponse.ok(agentToolCatalog.verifierCapabilities(), RequestIdFilter.currentRequestId());
    }

    private static ToolView toView(ToolDefinition tool) {
        return new ToolView(
                tool.name(),
                tool.executorKind(),
                tool.description(),
                tool.readOnly(),
                tool.requiresApproval(),
                tool.supportedTaskTypes().stream().map(Enum::name).toList(),
                tool.requiredParameters(),
                tool.targetSystems(),
                tool.inputSchema().isEmpty() ? null : tool.inputSchema());
    }

    public record ToolCatalogView(List<ToolView> planner, List<ToolView> executor, List<VerifierCapability> verifier) {}

    public record ToolView(
            String name,
            String executorKind,
            String description,
            boolean readOnly,
            boolean requiresApproval,
            List<String> supportedTaskTypes,
            List<String> requiredParameters,
            List<String> targetSystems,
            Map<String, Object> inputSchema) {}
}
