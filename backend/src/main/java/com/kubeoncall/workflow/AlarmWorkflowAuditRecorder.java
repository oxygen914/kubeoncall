package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.service.ExecutionAuditService;

@Service
public class AlarmWorkflowAuditRecorder {

    private final ExecutionAuditService executionAuditService;
    private final ObjectProvider<AlarmWorkflowFactRecorder> factRecorderProvider;

    public AlarmWorkflowAuditRecorder(ExecutionAuditService executionAuditService) {
        this(executionAuditService, null);
    }

    @Autowired
    public AlarmWorkflowAuditRecorder(
            ExecutionAuditService executionAuditService,
            ObjectProvider<AlarmWorkflowFactRecorder> factRecorderProvider) {
        this.executionAuditService = executionAuditService;
        this.factRecorderProvider = factRecorderProvider;
    }

    public void record(AuditRequest request) {
        executionAuditService.recordAlarmExecution(
                request.executionId(),
                request.status(),
                request.autoHandled(),
                request.approvalRequired(),
                request.summary(),
                request.failureReason(),
                request.tools(),
                request.startedAt(),
                metadata(
                        request.event(),
                        request.evaluation(),
                        request.activeState(),
                        request.memoryRecall(),
                        request.context(),
                        request.results(),
                        request.extras()));
        if (factRecorderProvider != null) {
            AlarmWorkflowFactRecorder recorder = factRecorderProvider.getIfAvailable();
            if (recorder != null) {
                recorder.record(request);
            }
        }
    }

    public void recordTerminal(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            NodeResult result,
            String status,
            boolean autoHandled,
            boolean approvalRequired,
            String summary,
            List<String> tools,
            Instant startedAt,
            Map<String, Object> extras) {
        com.kubeoncall.alarm.domain.NormalizedAlarmEvent event = preparedAlarm.event();
        record(new AuditRequest(
                event.alarmId() == null ? event.fingerprint() : event.alarmId(),
                status,
                autoHandled,
                approvalRequired,
                summary,
                null,
                tools,
                startedAt,
                event,
                preparedAlarm.evaluation(),
                preparedAlarm.activeState(),
                memoryRecall,
                null,
                List.of(result),
                extras));
    }

    public String summary(
            com.kubeoncall.alarm.domain.NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState activeState,
            AlertWorkflowMemory.Recall memoryRecall,
            AlertWorkflowContext context,
            NodeResult latest,
            String outcome) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, "alert", event.alertName());
        appendPart(builder, "fingerprint", event.fingerprint());
        appendPart(builder, "outcome", outcome);
        appendPart(
                builder,
                "severity",
                evaluation == null || evaluation.finalSeverity() == null
                        ? event.rawSeverity()
                        : evaluation.finalSeverity().name());
        appendPart(builder, "policy", evaluation == null ? null : evaluation.policyId());
        appendPart(builder, "template", evaluation == null ? null : evaluation.workflowTemplate());
        appendPart(builder, "resourceType", event.resourceType());
        appendPart(builder, "resource", event.resourceName());
        appendPart(builder, "service", event.service());
        appendPart(builder, "cluster", event.cluster());
        appendPart(builder, "namespace", event.namespace());
        appendPart(builder, "activeCount", activeState == null ? null : activeState.count());
        appendPart(
                builder,
                "memoryRecallCount",
                memoryRecall == null ? 0 : memoryRecall.entries().size());
        if (context != null) {
            appendPart(builder, "failedNodes", context.getFailedNodes());
            appendPart(builder, "skippedNodes", context.getSkippedNodes());
            appendPart(builder, "degraded", context.isDegraded());
            appendPart(builder, "silenceApproved", context.getAttribute("silenceApproved"));
            appendPart(builder, "memoryConsumedCount", context.getAttribute("alertMemoryConsumed"));
            appendPart(builder, "repeatIncident", context.getAttribute("repeatIncident"));
        }
        if (latest != null) {
            appendPart(builder, "latestNode", latest.nodeName());
            appendPart(builder, "latestStatus", latest.status());
            appendPart(builder, "latestMessage", latest.message());
        }
        return builder.toString();
    }

    public Map<String, Object> activeAlarmPayload(ActiveAlarmState activeState) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (activeState == null) {
            payload.put("present", false);
            return payload;
        }
        payload.put("present", true);
        payload.put("fingerprint", activeState.fingerprint());
        payload.put(
                "status",
                activeState.status() == null ? null : activeState.status().name());
        payload.put(
                "severity",
                activeState.severity() == null ? null : activeState.severity().name());
        payload.put(
                "firstSeen",
                activeState.firstSeen() == null ? null : activeState.firstSeen().toString());
        payload.put(
                "lastSeen",
                activeState.lastSeen() == null ? null : activeState.lastSeen().toString());
        payload.put("count", activeState.count());
        return payload;
    }

    public LinkedHashMap<String, Object> recoveryPayload(AlarmRecoveryState state) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("fingerprint", state.fingerprint());
        payload.put("status", state.status());
        payload.put(
                "severity", state.severity() == null ? null : state.severity().name());
        payload.put("policyId", state.policyId());
        payload.put("recoverExpression", state.recoverExpression());
        payload.put("candidateAt", state.candidateAt().toString());
        payload.put("confirmAfter", state.confirmAfter().toString());
        payload.put("manualConfirmationRequired", state.manualConfirmationRequired());
        return payload;
    }

    private Map<String, Object> metadata(
            com.kubeoncall.alarm.domain.NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState activeState,
            AlertWorkflowMemory.Recall memoryRecall,
            AlertWorkflowContext context,
            List<NodeResult> results,
            Map<String, Object> extras) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "fingerprint", event.fingerprint());
        putIfPresent(metadata, "alarmId", event.alarmId());
        putIfPresent(metadata, "alertName", event.alertName());
        putIfPresent(metadata, "source", event.source());
        putIfPresent(metadata, "status", event.status());
        putIfPresent(metadata, "resourceType", event.resourceType());
        putIfPresent(metadata, "resourceName", event.resourceName());
        putIfPresent(metadata, "cluster", event.cluster());
        putIfPresent(metadata, "namespace", event.namespace());
        putIfPresent(metadata, "service", event.service());
        putIfPresent(metadata, "metricName", event.metricName());
        putIfPresent(metadata, "runbookId", event.runbookId());
        if (evaluation != null) {
            putIfPresent(metadata, "policyId", evaluation.policyId());
            putIfPresent(
                    metadata,
                    "policyVersion",
                    evaluation.matchedPolicy() == null
                            ? null
                            : evaluation.matchedPolicy().version());
            putIfPresent(metadata, "policyMatched", evaluation.matched());
            putIfPresent(metadata, "severity", evaluation.finalSeverity());
            putIfPresent(metadata, "workflowTemplate", evaluation.workflowTemplate());
            putIfPresent(
                    metadata,
                    "policyCategory",
                    evaluation.matchedPolicy() == null
                            ? null
                            : evaluation.matchedPolicy().category());
            putIfPresent(metadata, "policyReason", evaluation.reason());
            putIfPresent(metadata, "policyRagFilters", evaluation.ragFilters());
        }
        if (activeState != null) {
            putIfPresent(metadata, "activeStatus", activeState.status());
            putIfPresent(metadata, "activeSeverity", activeState.severity());
            putIfPresent(metadata, "activeCount", activeState.count());
            putIfPresent(
                    metadata,
                    "firstSeen",
                    activeState.firstSeen() == null
                            ? null
                            : activeState.firstSeen().toString());
            putIfPresent(
                    metadata,
                    "lastSeen",
                    activeState.lastSeen() == null
                            ? null
                            : activeState.lastSeen().toString());
        }
        if (memoryRecall != null) {
            putIfPresent(
                    metadata, "alertMemoryRecallCount", memoryRecall.entries().size());
            putIfPresent(
                    metadata,
                    "alertMemoryIds",
                    memoryRecall.entries().stream().map(entry -> entry.id()).toList());
            putIfPresent(metadata, "alertMemoryWarning", memoryRecall.warning());
        }
        if (context != null) {
            putIfPresent(metadata, "degraded", context.isDegraded());
            putIfPresent(metadata, "failedNodes", context.getFailedNodes());
            putIfPresent(metadata, "skippedNodes", context.getSkippedNodes());
            putIfPresent(
                    metadata, "alertMemoryExtractionWarning", context.getAttribute("alertMemoryExtractionWarning"));
            putIfPresent(metadata, "alertMemoryConsumed", context.getAttribute("alertMemoryConsumed"));
            putIfPresent(metadata, "alertMemoryConsumedIds", context.getAttribute("alertMemoryConsumedIds"));
            putIfPresent(metadata, "repeatIncident", context.getAttribute("repeatIncident"));
            putIfPresent(metadata, "activatedSkillIds", context.getAttribute("activatedSkillIds"));
            putIfPresent(metadata, "activatedSkillMatchSources", context.getAttribute("activatedSkillMatchSources"));
            putIfPresent(metadata, "activatedSkillMaxRisk", context.getAttribute("activatedSkillMaxRisk"));
            putIfPresent(metadata, "activatedSkillToolWhitelist", context.getAttribute("activatedSkillToolWhitelist"));
            putIfPresent(metadata, "skillDiagnosisPlan", context.getAttribute("skillDiagnosisPlan"));
            putIfPresent(metadata, "skillDiagnosisVerification", context.getAttribute("skillDiagnosisVerification"));
            putIfPresent(metadata, "skillDiagnosisFallback", context.getAttribute("skillDiagnosisFallback"));
            putIfPresent(metadata, "skillDiagnosisInvokedTools", context.getAttribute("skillDiagnosisInvokedTools"));
            putIfPresent(
                    metadata, "skillDiagnosisAgentExecutionId", context.getAttribute("skillDiagnosisAgentExecutionId"));
            putIfPresent(metadata, "silenceApproved", context.getAttribute("silenceApproved"));
            putIfPresent(metadata, "silenceApprovedBy", context.getAttribute("silenceApprovedBy"));
            putIfPresent(metadata, "silenceApprovalReason", context.getAttribute("silenceApprovalReason"));
            putIfPresent(metadata, "silenceApprovalExpiresAt", context.getAttribute("silenceApprovalExpiresAt"));
            putIfPresent(metadata, "silenceApprovalKey", context.getAttribute("silenceApprovalKey"));
            putIfPresent(metadata, "silenceApprovalWarning", context.getAttribute("silenceApprovalWarning"));
            putIfPresent(metadata, "changeCorrelations", context.getAttribute("changeCorrelations"));
            putIfPresent(metadata, "topChangeCorrelation", context.getAttribute("topChangeCorrelation"));
            putIfPresent(metadata, "changeCorrelationWarning", context.getAttribute("changeCorrelationWarning"));
            putIfPresent(metadata, "aggregationKey", context.getAttribute("aggregationKey"));
            putIfPresent(metadata, "aggregationCount", context.getAttribute("aggregationCount"));
            putIfPresent(metadata, "aggregationWindowSeconds", context.getAttribute("aggregationWindowSeconds"));
            putIfPresent(metadata, "aggregationRepresentative", context.getAttribute("aggregationRepresentative"));
        }
        if (results != null && !results.isEmpty()) {
            putIfPresent(metadata, "nodeResultCount", results.size());
            putIfPresent(
                    metadata,
                    "nodeNames",
                    results.stream().map(NodeResult::nodeName).toList());
            NodeResult latest = results.get(results.size() - 1);
            putIfPresent(metadata, "latestNode", latest.nodeName());
            putIfPresent(metadata, "latestNodeStatus", latest.status());
        }
        if (extras != null) {
            extras.forEach((key, value) -> putIfPresent(metadata, key, value));
        }
        return metadata;
    }

    private void appendPart(StringBuilder builder, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("; ");
        }
        builder.append(key).append('=').append(text);
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return;
        }
        metadata.put(key, value);
    }

    public record AuditRequest(
            String executionId,
            String status,
            boolean autoHandled,
            boolean approvalRequired,
            String summary,
            String failureReason,
            List<String> tools,
            Instant startedAt,
            com.kubeoncall.alarm.domain.NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState activeState,
            AlertWorkflowMemory.Recall memoryRecall,
            AlertWorkflowContext context,
            List<NodeResult> results,
            Map<String, Object> extras) {}
}
