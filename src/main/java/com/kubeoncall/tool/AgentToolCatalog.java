package com.kubeoncall.tool;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.mcp.McpToolRegistry;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AgentToolCatalog {

    private final List<ToolExecutor> toolExecutors;
    private final McpToolRegistry mcpToolRegistry;

    public AgentToolCatalog(List<ToolExecutor> toolExecutors, McpToolRegistry mcpToolRegistry) {
        this.toolExecutors = toolExecutors;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    public List<ToolDefinition> plannerTools() {
        return mcpToolRegistry.listPlannerTools();
    }

    public List<ToolDefinition> executorTools() {
        return toolExecutors.stream()
                .flatMap(executor -> executor.supportedTools().stream())
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    public List<Map<String, Object>> verifierCapabilities() {
        return List.of(
                Map.of(
                        "node", "verifierThinkNode",
                        "type", "policy",
                        "description", "Checks SOP presence, task risk, executor tool compliance, and red-line operations before execution"
                ),
                Map.of(
                        "node", "verifierApprovalNode",
                        "type", "human_approval",
                        "description", "Suspends execution for human review when risk or policy requires approval"
                )
        );
    }

    public Map<String, ToolDefinition> allToolsByName() {
        return allTools().stream()
                .collect(Collectors.toMap(ToolDefinition::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    public Map<String, List<ToolDefinition>> toolsByExecutorKind() {
        return executorTools().stream()
                .collect(Collectors.groupingBy(ToolDefinition::executorKind, LinkedHashMap::new, Collectors.toList()));
    }

    public ToolDefinition findExecutorTool(String executorKind, String action) {
        String toolName = executorKind + "." + action;
        return allToolsByName().get(toolName);
    }

    public boolean isPlannerReadOnly() {
        return plannerTools().stream().allMatch(ToolDefinition::readOnly);
    }

    private List<ToolDefinition> allTools() {
        return java.util.stream.Stream.concat(plannerTools().stream(), executorTools().stream()).toList();
    }

    public List<TaskType> taskTypesFor(String toolName) {
        ToolDefinition definition = allToolsByName().get(toolName);
        return definition == null ? List.of() : definition.supportedTaskTypes();
    }
}
