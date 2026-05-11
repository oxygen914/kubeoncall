package com.kubeoncall.workflow.node;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DeviceInfoNode implements AlertWorkflowNode {

    private final Map<String, ToolExecutor> executorsByKind;
    private final KubeOnCallProperties properties;

    public DeviceInfoNode(List<ToolExecutor> toolExecutors, KubeOnCallProperties properties) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.properties = properties;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        ToolExecutor device = executorsByKind.get("device");
        if (device == null) {
            return new NodeResult("deviceInfoNode", NodeStatus.FAILURE, "Device tool executor is not configured", Map.of("executorKind", "device"));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("scriptName", "collect_device_info");
        parameters.put("readonly", true);
        parameters.put("nodeName", context.getAlarmEvent().nodeName());

        Map<String, Object> toolResult = device.execute("executeScript", parameters);
        int httpStatus = readHttpStatus(toolResult);
        if (httpStatus >= 400 || "failed".equalsIgnoreCase(String.valueOf(toolResult.get("status")))) {
            return new NodeResult(
                    "deviceInfoNode",
                    NodeStatus.FAILURE,
                    "Failed to fetch device info",
                    Map.of("executorKind", "device", "action", "executeScript", "result", toolResult)
            );
        }

        context.putAttribute("targetNode", context.getAlarmEvent().nodeName());
        context.putAttribute("deviceInfoResult", toolResult);
        return new NodeResult(
                "deviceInfoNode",
                NodeStatus.SUCCESS,
                "Fetched device info",
                Map.of(
                        "nodeName", context.getAlarmEvent().nodeName(),
                        "severity", context.getAlarmEvent().severity(),
                        "executorKind", "device",
                        "action", "executeScript",
                        "result", toolResult
                )
        );
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return properties.getIntegrations().getDevice().getTimeoutMillis() > 0 ? 200 : 500;
    }
}
