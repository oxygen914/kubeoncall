package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.aggregation.AlarmAggregationService;
import com.kubeoncall.alarm.correlation.ChangeCorrelationService;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

/** Executes the configured workflow after preflight and records its final outcome. */
@Service
public class AlertWorkflowCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AlertWorkflowCoordinator.class);

    private final AlertWorkflowRunner workflowRunner;
    private final AlarmWorkflowAuditRecorder auditRecorder;
    private final AlertWorkflowMemory workflowMemory;
    private final AlarmSilenceApprovalStore silenceApprovalStore;
    private final AlarmWorkflowEscalation workflowEscalation;
    private final ChangeCorrelationService changeCorrelationService;
    private final AlarmAggregationService aggregationService;

    @Autowired
    public AlertWorkflowCoordinator(
            AlertWorkflowRunner workflowRunner,
            AlarmWorkflowAuditRecorder auditRecorder,
            AlertWorkflowMemory workflowMemory,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmWorkflowEscalation workflowEscalation,
            ChangeCorrelationService changeCorrelationService,
            AlarmAggregationService aggregationService) {
        this.workflowRunner = workflowRunner;
        this.auditRecorder = auditRecorder;
        this.workflowMemory = workflowMemory;
        this.silenceApprovalStore = silenceApprovalStore;
        this.workflowEscalation = workflowEscalation;
        this.changeCorrelationService = changeCorrelationService;
        this.aggregationService = aggregationService;
    }

    /** Retained for focused unit tests that do not need the optional correlation boundary. */
    public AlertWorkflowCoordinator(
            AlertWorkflowRunner workflowRunner,
            AlarmWorkflowAuditRecorder auditRecorder,
            AlertWorkflowMemory workflowMemory,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmWorkflowEscalation workflowEscalation) {
        this(workflowRunner, auditRecorder, workflowMemory, silenceApprovalStore, workflowEscalation, null, null);
    }

    public List<NodeResult> run(AlertWorkflowPreflight.Result preflightResult, Instant startedAt) {
        AlarmEventPreparationService.PreparedAlarm preparedAlarm = preflightResult.preparedAlarm();
        NormalizedAlarmEvent event = preparedAlarm.event();
        AlarmEvaluationResult evaluation = preparedAlarm.evaluation();
        AlertWorkflowContext context =
                new AlertWorkflowContext(toLegacy(event, evaluation), event, evaluation, startedAt);
        context.putAttribute("activeAlarm", preparedAlarm.activeState());
        workflowMemory.attach(context, preflightResult.memoryRecall());
        attachSilenceApproval(preparedAlarm.fingerprint(), context);
        attachChangeCorrelations(event, context);
        boolean representative = attachAggregation(event, evaluation, context);
        if (representative) {
            workflowRunner.run(context, evaluation);
            workflowEscalation.addResult(
                    event, evaluation, preparedAlarm.activeState(), preparedAlarm.fingerprint(), context);
        } else {
            context.addNodeResult(new NodeResult(
                    "alarmAggregation",
                    NodeStatus.SUCCESS,
                    "Alarm merged into an existing aggregation window",
                    Map.of(
                            "aggregationKey", context.getAttribute("aggregationKey"),
                            "aggregationCount", context.getAttribute("aggregationCount"))));
        }

        List<NodeResult> results = ensureResults(context, preparedAlarm);
        NodeResult latest = results.get(results.size() - 1);
        boolean noIssues =
                context.getFailedNodes().isEmpty() && context.getSkippedNodes().isEmpty();
        String status = noIssues ? "SUCCESS" : "DEGRADED";
        String failureReason = noIssues
                ? null
                : "Failed nodes: " + String.join(", ", context.getFailedNodes()) + "; Skipped nodes: "
                        + String.join(", ", context.getSkippedNodes());
        String handlingSummary = auditRecorder.summary(
                event,
                evaluation,
                preparedAlarm.activeState(),
                preflightResult.memoryRecall(),
                context,
                latest,
                status);
        workflowMemory.extract(event, handlingSummary, context);
        auditRecorder.record(new AlarmWorkflowAuditRecorder.AuditRequest(
                event.alarmId() == null ? event.fingerprint() : event.alarmId(),
                status,
                noIssues,
                false,
                handlingSummary,
                failureReason,
                toolNames(results, preflightResult.memoryRecall(), context),
                startedAt,
                event,
                evaluation,
                preparedAlarm.activeState(),
                preflightResult.memoryRecall(),
                context,
                results,
                Map.of("workflowStatus", status)));
        return results;
    }

    private List<NodeResult> ensureResults(
            AlertWorkflowContext context, AlarmEventPreparationService.PreparedAlarm preparedAlarm) {
        List<NodeResult> results = context.getNodeResults();
        if (!results.isEmpty()) {
            return results;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("fingerprint", preparedAlarm.fingerprint());
        payload.put("policyId", preparedAlarm.evaluation().policyId());
        payload.put("policyMatched", preparedAlarm.evaluation().matched());
        context.addNodeResult(
                new NodeResult("workflowEmpty", NodeStatus.SUCCESS, "No workflow nodes configured", payload));
        return context.getNodeResults();
    }

    private List<String> toolNames(
            List<NodeResult> results, AlertWorkflowMemory.Recall memoryRecall, AlertWorkflowContext context) {
        LinkedHashSet<String> toolNames = new LinkedHashSet<>();
        for (NodeResult result : results) {
            if (result.payload() == null) {
                continue;
            }
            addToolName(
                    toolNames,
                    result.payload().get("executorKind"),
                    result.payload().get("action"));
            Object resultPayload = result.payload().get("result");
            if (resultPayload instanceof Map<?, ?> payload) {
                addToolName(toolNames, payload.get("executor"), payload.get("action"));
            }
            if (Boolean.TRUE.equals(result.payload().get("dedupHit"))) {
                toolNames.add("alarm.dedup.hit");
            }
            Object invokedTools = result.payload().get("invokedTools");
            if (invokedTools instanceof List<?> values) {
                values.stream()
                        .map(String::valueOf)
                        .filter(value -> !value.isBlank())
                        .forEach(toolNames::add);
            }
        }
        if (!memoryRecall.entries().isEmpty()) {
            toolNames.add("memory.alert.recall");
        }
        if (!memoryRecall.warning().isBlank()) {
            toolNames.add("memory.alert.recall_failed");
        }
        if (workflowMemory.consumedCount(context) > 0) {
            toolNames.add("memory.alert.consume");
        }
        if (toolNames.isEmpty()) {
            toolNames.addAll(results.stream().map(NodeResult::nodeName).toList());
        }
        return new ArrayList<>(toolNames);
    }

    private void addToolName(LinkedHashSet<String> toolNames, Object executor, Object action) {
        if (executor != null && action != null) {
            toolNames.add(executor + "." + action);
        }
    }

    private void attachSilenceApproval(String fingerprint, AlertWorkflowContext context) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        try {
            silenceApprovalStore.find(fingerprint).ifPresent(approval -> {
                context.putAttribute("silenceApproved", true);
                context.putAttribute("silenceApprovedBy", approval.approvedBy());
                context.putAttribute("silenceApprovalReason", approval.reason());
                context.putAttribute(
                        "silenceApprovalExpiresAt",
                        approval.expiresAt() == null
                                ? null
                                : approval.expiresAt().toString());
                context.putAttribute("silenceApprovalKey", silenceApprovalStore.keyFor(fingerprint));
            });
        } catch (RuntimeException ex) {
            log.warn(
                    "Silence approval lookup failed; continuing without approval: errorType={}",
                    ex.getClass().getSimpleName());
            context.putAttribute("silenceApprovalWarning", "silence approval lookup failed");
        }
    }

    private void attachChangeCorrelations(NormalizedAlarmEvent event, AlertWorkflowContext context) {
        if (changeCorrelationService == null) {
            return;
        }
        try {
            List<com.kubeoncall.alarm.correlation.ChangeCorrelation> correlations =
                    changeCorrelationService.findRelatedChanges(event);
            context.putAttribute("changeCorrelations", correlations);
            if (!correlations.isEmpty()) {
                context.putAttribute("topChangeCorrelation", correlations.get(0));
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Change correlation lookup failed: errorType={}",
                    ex.getClass().getSimpleName());
            context.putAttribute("changeCorrelationWarning", "change correlation lookup failed");
        }
    }

    private boolean attachAggregation(
            NormalizedAlarmEvent event, AlarmEvaluationResult evaluation, AlertWorkflowContext context) {
        if (aggregationService == null) {
            return true;
        }
        AlarmAggregationService.AggregationDecision decision = aggregationService.evaluate(event, evaluation);
        context.putAttribute("aggregationKey", decision.key());
        context.putAttribute("aggregationCount", decision.count());
        context.putAttribute("aggregationWindowSeconds", decision.window().toSeconds());
        context.putAttribute("aggregationRepresentative", decision.representative());
        return decision.representative();
    }

    private AlarmEvent toLegacy(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation) {
        String severity =
                evaluation.finalSeverity() != null ? evaluation.finalSeverity().name() : event.rawSeverity();
        return new AlarmEvent(
                event.alarmId(),
                event.fingerprint(),
                event.source(),
                severity,
                event.resourceName(),
                event.summary(),
                event.occurredAt(),
                event.metadata());
    }
}
