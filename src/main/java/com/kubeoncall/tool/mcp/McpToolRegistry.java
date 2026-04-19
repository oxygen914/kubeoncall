package com.kubeoncall.tool.mcp;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpToolRegistry {

    public List<ToolDefinition> listPlannerTools() {
        return List.of(
                new ToolDefinition(
                        "knowledge.searchSop",
                        "knowledge",
                        "Search SOPs, runbooks, and historical remediation knowledge",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("query"),
                        List.of("knowledge-base")
                ),
                new ToolDefinition(
                        "topology.getServiceTopology",
                        "topology",
                        "Fetch upstream and downstream service dependency topology",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("serviceName"),
                        List.of("cmdb", "service-mesh")
                ),
                new ToolDefinition(
                        "cmdb.getServiceMetadata",
                        "cmdb",
                        "Load service ownership, namespace, environment, and criticality metadata",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("serviceName"),
                        List.of("cmdb")
                ),
                new ToolDefinition(
                        "kubernetes.describeResource",
                        "kubernetes",
                        "Describe Kubernetes resource metadata without changing cluster state",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS, TaskType.RESTART_SERVICE, TaskType.SCALE_WORKLOAD, TaskType.PATCH_CONFIG),
                        List.of("namespace", "resourceName"),
                        List.of("k8s-api")
                ),
                new ToolDefinition(
                        "prometheus.queryRange",
                        "prometheus",
                        "Query historical metrics trends for diagnostics and planning",
                        true,
                        false,
                        List.of(TaskType.QUERY_METRICS, TaskType.RESTART_SERVICE, TaskType.SCALE_WORKLOAD, TaskType.PATCH_CONFIG),
                        List.of("query", "windowMinutes"),
                        List.of("prometheus")
                ),
                new ToolDefinition(
                        "alerts.getActiveAlerts",
                        "alerts",
                        "Fetch active alerts, silences, and routing labels for the target service",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("serviceName"),
                        List.of("alertmanager")
                )
        );
    }
}
