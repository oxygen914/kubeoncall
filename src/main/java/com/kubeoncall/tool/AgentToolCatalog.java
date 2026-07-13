package com.kubeoncall.tool;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.mcp.McpToolRegistry;

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

    public List<ToolDefinition> executorTools(List<String> toolWhitelist) {
        List<ToolDefinition> tools = executorTools();
        if (toolWhitelist == null || toolWhitelist.isEmpty()) {
            return tools;
        }
        return tools.stream()
                .filter(tool -> isWhitelisted(tool.name(), toolWhitelist))
                .toList();
    }

    public List<Map<String, Object>> verifierCapabilities() {
        return List.of(
                Map.of(
                        "node", "verifierThinkNode",
                        "type", "policy",
                        "description",
                                "Checks SOP presence, task risk, executor tool compliance, and red-line operations before execution"),
                Map.of(
                        "node", "verifierApprovalNode",
                        "type", "human_approval",
                        "description", "Suspends execution for human review when risk or policy requires approval"));
    }

    public Map<String, ToolDefinition> allToolsByName() {
        return allTools().stream()
                .collect(Collectors.toMap(
                        ToolDefinition::name, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    public Map<String, List<ToolDefinition>> toolsByExecutorKind() {
        return executorTools().stream()
                .collect(Collectors.groupingBy(ToolDefinition::executorKind, LinkedHashMap::new, Collectors.toList()));
    }

    public ToolDefinition findExecutorTool(String executorKind, String action) {
        String toolName = executorKind + "." + action;
        return allToolsByName().get(toolName);
    }

    public ToolDefinition findExecutorTool(String executorKind, String action, List<String> toolWhitelist) {
        ToolDefinition definition = findExecutorTool(executorKind, action);
        if (definition == null) {
            return null;
        }
        if (toolWhitelist == null || toolWhitelist.isEmpty() || isWhitelisted(definition.name(), toolWhitelist)) {
            return definition;
        }
        return null;
    }

    public boolean isPlannerReadOnly() {
        return plannerTools().stream().allMatch(ToolDefinition::readOnly);
    }

    private List<ToolDefinition> allTools() {
        return java.util.stream.Stream.concat(plannerTools().stream(), executorTools().stream())
                .toList();
    }

    public List<TaskType> taskTypesFor(String toolName) {
        ToolDefinition definition = allToolsByName().get(toolName);
        return definition == null ? List.of() : definition.supportedTaskTypes();
    }

    private boolean isWhitelisted(String toolName, List<String> toolWhitelist) {
        if (toolWhitelist == null || toolWhitelist.isEmpty()) {
            return true;
        }
        return toolWhitelist.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .anyMatch(entry -> entry.equals(toolName)
                        || entry.endsWith(".*") && toolName.startsWith(entry.substring(0, entry.length() - 1)));
    }
}
