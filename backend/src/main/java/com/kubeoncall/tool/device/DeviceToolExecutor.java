package com.kubeoncall.tool.device;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class DeviceToolExecutor implements ToolExecutor {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public DeviceToolExecutor(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public String getExecutorKind() {
        return "device";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        return List.of(new ToolDefinition(
                "device.executeScript",
                "device",
                "Execute a guarded operational script on a managed device endpoint",
                false,
                true,
                List.of(TaskType.EXECUTE_SCRIPT),
                List.of("scriptName", "readonly"),
                List.of("device-gateway")));
    }

    @Override
    public Map<String, Object> execute(String action, Map<String, Object> parameters) {
        Map<String, Object> request = Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executor", getExecutorKind());
        metadata.put("action", action);
        metadata.put("parameters", parameters);
        metadata.put("targetSystem", "device-gateway");
        return toolHttpClient.post(
                properties.getIntegrations().getDevice().getEndpoint(),
                request,
                properties.getIntegrations().getDevice().getTimeoutMillis(),
                metadata);
    }
}
