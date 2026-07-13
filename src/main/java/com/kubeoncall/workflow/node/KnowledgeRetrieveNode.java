package com.kubeoncall.workflow.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;

@Component
public class KnowledgeRetrieveNode implements AlertWorkflowNode {

    private final KnowledgeIngestService knowledgeIngestService;
    private final Map<String, ToolExecutor> executorsByKind;
    private final KubeOnCallProperties properties;

    public KnowledgeRetrieveNode(
            KnowledgeIngestService knowledgeIngestService,
            List<ToolExecutor> toolExecutors,
            KubeOnCallProperties properties) {
        this.knowledgeIngestService = knowledgeIngestService;
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.properties = properties;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, String> policyFilters = policyFilters(context);
        Map<String, String> strictFilters = strictFilters(context, policyFilters);
        String ragQuery = buildQuery(context);

        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("route", "RAG");
        hints.put("summary", context.getAlarmEvent().summary());
        hints.put("ragQuery", ragQuery);
        hints.put("ragStrictFilters", strictFilters);

        try {
            List<Map<String, Object>> attempts = new ArrayList<>();
            RetrievalResult retrieval = retrieve(ragQuery, strictFilters, "strict", attempts);
            Map<String, String> effectiveFilters = strictFilters;
            String fallback = "none";
            if (retrieval.documents().isEmpty()) {
                Map<String, String> runbookFilters = runbookBindingFilters(context, policyFilters);
                if (!runbookFilters.isEmpty() && !runbookFilters.equals(strictFilters)) {
                    retrieval = retrieve(ragQuery, runbookFilters, "runbook_binding", attempts);
                    effectiveFilters = runbookFilters;
                    fallback = retrieval.documents().isEmpty() ? "runbook_binding_empty" : "runbook_binding";
                }
            }
            hints.put("ragFilters", effectiveFilters);
            hints.put("ragFilterFallback", fallback);
            hints.put("ragRetrievalAttempts", attempts);
            hints.put("retrievalSummary", retrieval.summary());
            hints.put("retrievalReasons", retrieval.retrievalReasons());
            hints.put(
                    "documents",
                    retrieval.documents().stream()
                            .map(doc -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("id", doc.id());
                                item.put("title", doc.title());
                                item.put("source", doc.source());
                                item.put("runbookId", metadata(doc.metadata(), "runbookId"));
                                item.put("documentType", metadata(doc.metadata(), "document_type"));
                                item.put("category", metadata(doc.metadata(), "category"));
                                return item;
                            })
                            .toList());
            hints.put("diagnostics", retrieval.diagnostics());
        } catch (RuntimeException ex) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("route", "RAG");
            failure.put("ragQuery", ragQuery);
            failure.put("ragStrictFilters", strictFilters);
            failure.put("errorType", ex.getClass().getSimpleName());
            failure.put("errorMessage", ex.getMessage() == null ? "" : ex.getMessage());
            return new NodeResult(
                    "knowledgeRetrieveNode", NodeStatus.FAILURE, "Failed to retrieve SOP knowledge", failure);
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
        return new NodeResult("knowledgeRetrieveNode", NodeStatus.SUCCESS, "Retrieved related SOP knowledge", hints);
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return properties.getIntegrations().getPrometheus().getTimeoutMillis() > 0 ? 200 : 500;
    }

    private RetrievalResult retrieve(
            String query, Map<String, String> filters, String strategy, List<Map<String, Object>> attempts) {
        RetrievalResult result = knowledgeIngestService.retrieve(query, filters);
        attempts.add(Map.of(
                "strategy",
                strategy,
                "filters",
                Map.copyOf(filters),
                "resultCount",
                result.documents() == null ? 0 : result.documents().size()));
        return result;
    }

    private Map<String, String> policyFilters(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        return evaluation == null ? Map.of() : evaluation.ragFilters();
    }

    private Map<String, String> strictFilters(AlertWorkflowContext context, Map<String, String> policyFilters) {
        Map<String, String> filters = new LinkedHashMap<>();
        putAllNonBlank(filters, policyFilters);
        NormalizedAlarmEvent event = context.getNormalizedAlarm();
        if (event != null) {
            put(filters, "alertName", event.alertName());
            put(
                    filters,
                    "resourceType",
                    event.resourceType() == null
                            ? null
                            : event.resourceType().name().toLowerCase(Locale.ROOT));
            put(filters, "service", event.service());
        }
        put(filters, "runbookId", runbookId(context));
        put(filters, "nodeName", context.getAlarmEvent().nodeName());
        put(filters, "severity", severity(context));
        return Map.copyOf(filters);
    }

    private Map<String, String> runbookBindingFilters(AlertWorkflowContext context, Map<String, String> policyFilters) {
        Map<String, String> filters = new LinkedHashMap<>();
        putAllNonBlank(filters, policyFilters);
        put(filters, "runbookId", runbookId(context));
        return Map.copyOf(filters);
    }

    private String buildQuery(AlertWorkflowContext context) {
        StringBuilder query = new StringBuilder();
        append(query, context.getAlarmEvent().summary());
        NormalizedAlarmEvent event = context.getNormalizedAlarm();
        if (event != null) {
            append(query, event.alertName());
            append(
                    query,
                    event.resourceType() == null ? null : event.resourceType().name());
            append(query, event.service());
        }
        append(query, runbookId(context));
        return query.toString();
    }

    private String runbookId(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null
                && evaluation.runbookId() != null
                && !evaluation.runbookId().isBlank()) {
            return evaluation.runbookId();
        }
        NormalizedAlarmEvent event = context.getNormalizedAlarm();
        return event == null ? null : event.runbookId();
    }

    private String severity(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.finalSeverity() != null) {
            return evaluation.finalSeverity().name();
        }
        return context.getAlarmEvent().severity();
    }

    private void putAllNonBlank(Map<String, String> target, Map<String, String> values) {
        if (values == null) {
            return;
        }
        values.forEach((key, value) -> put(target, key, value));
    }

    private void put(Map<String, String> target, String key, String value) {
        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
            target.put(key.trim(), value.trim());
        }
    }

    private void append(StringBuilder query, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!query.isEmpty()) {
            query.append(' ');
        }
        query.append(value.trim());
    }

    private String metadata(Map<String, String> metadata, String key) {
        return metadata == null ? null : metadata.get(key);
    }
}
