package com.kubeoncall.workflow.node;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.rag.KnowledgeIngestService;
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
public class KnowledgeRetrieveNode implements AlertWorkflowNode {

    private final KnowledgeIngestService knowledgeIngestService;
    private final Map<String, ToolExecutor> executorsByKind;
    private final KubeOnCallProperties properties;

    public KnowledgeRetrieveNode(KnowledgeIngestService knowledgeIngestService,
                                 List<ToolExecutor> toolExecutors,
                                 KubeOnCallProperties properties) {
        this.knowledgeIngestService = knowledgeIngestService;
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.properties = properties;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, String> ragFilters = new LinkedHashMap<>();
        ragFilters.put("nodeName", context.getAlarmEvent().nodeName());
        ragFilters.put("severity", context.getAlarmEvent().severity());

        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("route", "RAG");
        hints.put("summary", context.getAlarmEvent().summary());

        try {
            var retrieval = knowledgeIngestService.retrieve(context.getAlarmEvent().summary(), ragFilters);
            hints.put("retrievalSummary", retrieval.summary());
            hints.put("retrievalReasons", retrieval.retrievalReasons());
            hints.put("documents", retrieval.documents().stream().map(doc -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", doc.id());
                item.put("title", doc.title());
                item.put("source", doc.source());
                return item;
            }).toList());
            hints.put("diagnostics", retrieval.diagnostics());
        } catch (RuntimeException ex) {
            return new NodeResult(
                    "knowledgeRetrieveNode",
                    NodeStatus.FAILURE,
                    "Failed to retrieve SOP knowledge",
                    Map.of("route", "RAG", "errorType", ex.getClass().getSimpleName(), "errorMessage", ex.getMessage())
            );
        }

        ToolExecutor prometheus = executorsByKind.get("prometheus");
        if (prometheus != null) {
            Map<String, Object> metricParams = new LinkedHashMap<>();
            metricParams.put("query", "up{instance=\"" + context.getAlarmEvent().nodeName() + "\"}");
            Map<String, Object> metricResult = prometheus.execute("instantQuery", metricParams);
            int httpStatus = readHttpStatus(metricResult);
            if (httpStatus < 400 && !"failed".equalsIgnoreCase(String.valueOf(metricResult.get("status")))) {
                hints.put("metricSnapshot", metricResult);
            }
        }

        context.putAttribute("knowledgeHints", hints);
        return new NodeResult(
                "knowledgeRetrieveNode",
                NodeStatus.SUCCESS,
                "Retrieved related SOP knowledge",
                hints
        );
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return properties.getIntegrations().getPrometheus().getTimeoutMillis() > 0 ? 200 : 500;
    }
}
