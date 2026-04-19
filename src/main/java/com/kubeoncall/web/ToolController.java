package com.kubeoncall.web;

import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.web.dto.ToolDefinitionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final AgentToolCatalog agentToolCatalog;

    public ToolController(AgentToolCatalog agentToolCatalog) {
        this.agentToolCatalog = agentToolCatalog;
    }

    @GetMapping
    public Map<String, Object> allTools() {
        return Map.of(
                "planner", toResponses(agentToolCatalog.plannerTools()),
                "executor", toResponses(agentToolCatalog.executorTools()),
                "verifier", agentToolCatalog.verifierCapabilities()
        );
    }

    @GetMapping("/planner")
    public List<ToolDefinitionResponse> plannerTools() {
        return toResponses(agentToolCatalog.plannerTools());
    }

    @GetMapping("/executor")
    public List<ToolDefinitionResponse> executorTools() {
        return toResponses(agentToolCatalog.executorTools());
    }

    @GetMapping("/verifier")
    public List<Map<String, Object>> verifierTools() {
        return agentToolCatalog.verifierCapabilities();
    }

    private List<ToolDefinitionResponse> toResponses(List<ToolDefinition> tools) {
        return tools.stream()
                .map(tool -> new ToolDefinitionResponse(
                        tool.name(),
                        tool.executorKind(),
                        tool.description(),
                        tool.readOnly(),
                        tool.requiresApproval(),
                        tool.supportedTaskTypes().stream().map(Enum::name).toList(),
                        tool.requiredParameters(),
                        tool.targetSystems()
                ))
                .toList();
    }
}
