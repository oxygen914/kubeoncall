package com.kubeoncall.tool.device;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DeviceToolExecutor implements ToolExecutor {

    @Override
    public String getExecutorKind() {
        return "device";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        return List.of(
                new ToolDefinition(
                        "device.executeScript",
                        "device",
                        "Execute a guarded operational script on a managed device endpoint",
                        false,
                        true,
                        List.of(TaskType.EXECUTE_SCRIPT),
                        List.of("scriptName", "readonly"),
                        List.of("device-gateway")
                )
        );
    }

    @Override
    public Map<String, Object> execute(String action, Map<String, Object> parameters) {
        return Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters,
                "targetSystem", "device-gateway",
                "status", "simulated_success",
                "exitCode", 0,
                "simulatedLatencyMs", 180
        );
    }
}
