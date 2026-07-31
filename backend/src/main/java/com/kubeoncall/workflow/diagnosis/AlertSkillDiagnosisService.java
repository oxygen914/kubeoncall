package com.kubeoncall.workflow.diagnosis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kubeoncall.agent.executor.ExecutorAgent;
import com.kubeoncall.agent.planner.PlannerAgent;
import com.kubeoncall.agent.verifier.VerifierThinkNode;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.evidence.AiConclusion;
import com.kubeoncall.evidence.ConfidenceAssessment;
import com.kubeoncall.evidence.EvidenceItem;
import com.kubeoncall.evidence.EvidenceType;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillExecutionPolicy;
import com.kubeoncall.workflow.AlertWorkflowContext;

/**
 * Runs automatic alarm diagnosis through the existing governed Agent components.
 *
 * <p>The alarm path is permanently read-only: the activated Skill whitelist and maxRisk are
 * re-applied after model planning, the normal executor/verifier gates run before dispatch, and no
 * approval or mutating operation is created. Any blocked or failed phase returns a deterministic
 * current-state/runbook fallback.
 */
@Service
public class AlertSkillDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(AlertSkillDiagnosisService.class);

    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final VerifierThinkNode verifierThinkNode;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;

    public AlertSkillDiagnosisService(
            PlannerAgent plannerAgent,
            ExecutorAgent executorAgent,
            VerifierThinkNode verifierThinkNode,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService) {
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.verifierThinkNode = verifierThinkNode;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    public Outcome diagnose(AlertWorkflowContext alertContext, SkillActivation activation) {
        if (!properties.getSkill().isAutomaticAlertDiagnosisEnabled() || activation == null || !activation.active()) {
            return Outcome.notAttempted();
        }

        GraphState state = prepareState(alertContext, activation);
        if (activation.toolWhitelist().isEmpty()) {
            return finish(state, alertContext, activation, "EMPTY_SKILL_TOOL_WHITELIST");
        }
        if (activation.maxRisk() == null) {
            return finish(state, alertContext, activation, "MISSING_SKILL_MAX_RISK");
        }

        try {
            plannerAgent.run(state);
            reapplyActivation(state, activation);
            if (state.getStatus() != GraphStatus.SUCCESS || state.getCurrentTask() == null) {
                return finish(state, alertContext, activation, "PLANNER_FAILED");
            }

            String taskViolation = taskViolation(state.getCurrentTask(), activation);
            if (taskViolation != null) {
                return finish(state, alertContext, activation, taskViolation);
            }

            executorAgent.plan(state);
            if (state.getStatus() != GraphStatus.SUCCESS) {
                return finish(state, alertContext, activation, "EXECUTION_PLAN_BLOCKED");
            }

            NodeResult verifierResult = verifierThinkNode.execute(state);
            state.addNodeResult(verifierResult);
            if (verifierResult.status() != NodeStatus.SUCCESS
                    || !"ALLOW".equals(state.getContext().get("verifierDecision"))) {
                state.setStatus(GraphStatus.FAILED);
                return finish(state, alertContext, activation, "VERIFIER_REJECTED");
            }

            state.setCurrentLoop(0);
            executorAgent.executePrepared(state);
            if (state.getStatus() != GraphStatus.SUCCESS) {
                return finish(state, alertContext, activation, "READ_ONLY_QUERY_FAILED");
            }
            return finish(state, alertContext, activation, null);
        } catch (RuntimeException ex) {
            log.warn(
                    "Automatic alarm Skill diagnosis failed; using deterministic fallback: errorType={}",
                    ex.getClass().getSimpleName());
            state.setStatus(GraphStatus.FAILED);
            return finish(state, alertContext, activation, "AGENT_DIAGNOSIS_ERROR");
        }
    }

    private GraphState prepareState(AlertWorkflowContext alertContext, SkillActivation activation) {
        NormalizedAlarmEvent event = alertContext.getNormalizedAlarm();
        GraphState state = new GraphState();
        state.setExecutionId(agentExecutionId(alertContext));
        state.setUserRequest(diagnosticRequest(event, runbookId(alertContext)));
        state.getContext().put("automaticAlertDiagnosis", true);
        state.getContext().put("compatibilityReadOnly", true);
        state.getContext().put("requestScope", requestScope(event));
        state.getContext().put("plannerKnowledge", plannerKnowledge(alertContext));
        reapplyActivation(state, activation);
        return state;
    }

    private void reapplyActivation(GraphState state, SkillActivation activation) {
        state.getContext().put("activatedSkillIds", activation.skillIds());
        state.getContext().put("activatedSkills", activation.skillSummaries());
        state.getContext().put("activatedSkillMatchSources", activation.matchSources());
        state.getContext().put("activatedSkillToolWhitelist", activation.toolWhitelist());
        state.getContext().put("skillPrompt", activation.prompt());
        if (activation.maxRisk() != null) {
            state.getContext().put("activatedSkillMaxRisk", activation.maxRisk().name());
        }
        Map<String, Object> knowledge = map(state.getContext().get("plannerKnowledge"));
        knowledge.put("activatedSkillIds", activation.skillIds());
        knowledge.put("activatedSkills", activation.skillSummaries());
        knowledge.put("activatedSkillMatchSources", activation.matchSources());
        knowledge.put("activatedSkillToolWhitelist", activation.toolWhitelist());
        knowledge.put(
                "activatedSkillMaxRisk",
                activation.maxRisk() == null ? "" : activation.maxRisk().name());
        knowledge.put("skillPrompt", activation.prompt());
        knowledge.put("automaticAlertDiagnosis", true);
        knowledge.put("readOnlyRequired", true);
        state.getContext().put("plannerKnowledge", knowledge);
    }

    private String taskViolation(Task task, SkillActivation activation) {
        if (task.taskType() == null || !task.taskType().name().startsWith("QUERY_")) {
            return "NON_READ_ONLY_TASK_BLOCKED";
        }
        if (task.riskLevel() == null) {
            return "MISSING_TASK_RISK";
        }
        if (SkillExecutionPolicy.exceedsMaxRisk(task.riskLevel(), activation.maxRisk())) {
            return "SKILL_MAX_RISK_EXCEEDED";
        }
        boolean supported = activation.skills().stream()
                .anyMatch(skill -> skill.applicableTasks().contains(task.taskType()));
        return supported ? null : "TASK_NOT_SUPPORTED_BY_SKILL";
    }

    private Outcome finish(
            GraphState state, AlertWorkflowContext alertContext, SkillActivation activation, String blockedReason) {
        List<EvidenceItem> evidence = typed(state.getContext().get("evidenceItems"), EvidenceItem.class);
        List<AiConclusion> conclusions = typed(state.getContext().get("conclusions"), AiConclusion.class);
        List<?> conflicts = state.getContext().get("evidenceConflicts") instanceof List<?> values ? values : List.of();
        VerifierThinkNode.EvidenceVerification evidenceVerification = verifierThinkNode.verifyEvidence(state);
        long liveEvidenceCount = evidence.stream()
                .filter(EvidenceItem::succeeded)
                .filter(item -> item.type() != EvidenceType.SOP)
                .count();
        boolean baselineAvailable = successfulToolResult(alertContext.getAttribute("stateCompareResult"));
        boolean executorSucceeded = successfulToolResult(state.getContext().get("executorResult"));
        boolean currentEvidenceAvailable = liveEvidenceCount > 0 || baselineAvailable || executorSucceeded;

        String verificationStatus;
        if ("CONFLICTED".equals(evidenceVerification.status()) || !conflicts.isEmpty()) {
            verificationStatus = "CONFLICTED";
        } else if (blockedReason == null && executorSucceeded && evidenceVerification.verified()) {
            verificationStatus = "VERIFIED";
        } else if (currentEvidenceAvailable) {
            verificationStatus = "PARTIAL";
        } else {
            verificationStatus = "INSUFFICIENT";
        }

        boolean plannerDegraded = Boolean.TRUE.equals(state.getContext().get("plannerDegraded"));
        String fallbackReason = blockedReason;
        if (fallbackReason == null && plannerDegraded) {
            fallbackReason =
                    String.valueOf(state.getContext().getOrDefault("plannerDegradedReason", "PLANNER_DEGRADED"));
        }
        if (fallbackReason == null && !"VERIFIED".equals(verificationStatus)) {
            fallbackReason = "EVIDENCE_" + verificationStatus;
        }
        boolean fallbackUsed = fallbackReason != null;
        String strategy = strategy(verificationStatus, plannerDegraded, blockedReason);

        Map<String, Object> plan = plan(state, activation);
        List<String> invokedTools = invokedTools(state, evidence);
        Map<String, Object> verification = verification(
                state,
                evidence,
                conclusions,
                verificationStatus,
                liveEvidenceCount,
                baselineAvailable,
                executorSucceeded,
                evidenceVerification);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("used", fallbackUsed);
        put(fallback, "reason", fallbackReason);
        put(fallback, "runbookId", runbookId(alertContext));
        fallback.put("stateCompareAvailable", baselineAvailable);
        fallback.put("knowledgeAvailable", alertContext.getAttribute("knowledgeHints") != null);
        if (fallbackUsed) {
            fallback.put("mode", "CURRENT_STATE_AND_RUNBOOK");
            fallback.put("baselineDiagnosis", baselineDiagnosis(alertContext));
        }

        metricsService.recordSkillGovernance(
                "automatic_alert_diagnosis", verificationStatus.toLowerCase(java.util.Locale.ROOT));
        return new Outcome(
                true,
                strategy,
                "VERIFIED".equals(verificationStatus),
                plan,
                verification,
                fallback,
                invokedTools,
                evidence,
                conclusions,
                state.getExecutionId());
    }

    private Map<String, Object> baselineDiagnosis(AlertWorkflowContext context) {
        NormalizedAlarmEvent event = context.getNormalizedAlarm();
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        put(diagnosis, "alertName", event == null ? null : event.alertName());
        put(
                diagnosis,
                "resourceType",
                event == null || event.resourceType() == null
                        ? null
                        : event.resourceType().name());
        put(diagnosis, "resourceName", event == null ? null : event.resourceName());
        put(diagnosis, "cluster", event == null ? null : event.cluster());
        put(diagnosis, "namespace", event == null ? null : event.namespace());
        diagnosis.put("currentState", boundedToolSummary(context.getAttribute("stateCompareResult")));
        diagnosis.put("runbook", runbookSummary(context));
        diagnosis.put(
                "nextStep",
                successfulToolResult(context.getAttribute("stateCompareResult"))
                        ? "Review the current state comparison against the bound runbook; do not mutate automatically."
                        : "Current state evidence is incomplete; validate the alert manually with the bound runbook.");
        return Map.copyOf(diagnosis);
    }

    private Map<String, Object> boundedToolSummary(Object value) {
        if (!(value instanceof Map<?, ?> result)) {
            return Map.of("available", false);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", successfulToolResult(value));
        copyScalar(result, summary, "status");
        copyScalar(result, summary, "httpStatus");
        copyScalar(result, summary, "latencyMs");
        Object response = result.get("response");
        if (response instanceof Map<?, ?> responseMap
                && responseMap.get("data") instanceof Map<?, ?> data
                && data.get("result") instanceof List<?> series) {
            summary.put("seriesCount", series.size());
        }
        return Map.copyOf(summary);
    }

    private Map<String, Object> runbookSummary(AlertWorkflowContext context) {
        Map<String, Object> summary = new LinkedHashMap<>();
        put(summary, "runbookId", runbookId(context));
        Object knowledge = context.getAttribute("knowledgeHints");
        summary.put("available", knowledge instanceof Map<?, ?>);
        if (knowledge instanceof Map<?, ?> hints) {
            copyScalar(hints, summary, "route");
            copyScalar(hints, summary, "ragFilterFallback");
            if (hints.get("documents") instanceof List<?> documents) {
                summary.put("documentCount", documents.size());
            }
        }
        return Map.copyOf(summary);
    }

    private Map<String, Object> plan(GraphState state, SkillActivation activation) {
        Map<String, Object> plan = new LinkedHashMap<>();
        put(plan, "source", state.getContext().get("plannerSource"));
        put(plan, "mode", state.getContext().get("plannerMode"));
        plan.put("degraded", Boolean.TRUE.equals(state.getContext().get("plannerDegraded")));
        put(plan, "degradedReason", state.getContext().get("plannerDegradedReason"));
        put(plan, "provider", state.getContext().get("plannerProvider"));
        put(plan, "model", state.getContext().get("plannerModel"));
        Task task = state.getCurrentTask();
        if (task != null) {
            put(
                    plan,
                    "taskType",
                    task.taskType() == null ? null : task.taskType().name());
            put(
                    plan,
                    "riskLevel",
                    task.riskLevel() == null ? null : task.riskLevel().name());
            put(plan, "target", task.target());
            put(plan, "summary", task.description());
        }
        put(plan, "selectedTool", selectedTool(state));
        plan.put("readOnlyRequired", true);
        plan.put("allowedTools", activation.toolWhitelist());
        put(
                plan,
                "maxRisk",
                activation.maxRisk() == null ? null : activation.maxRisk().name());
        return Map.copyOf(plan);
    }

    private Map<String, Object> verification(
            GraphState state,
            List<EvidenceItem> evidence,
            List<AiConclusion> conclusions,
            String status,
            long liveEvidenceCount,
            boolean baselineAvailable,
            boolean executorSucceeded,
            VerifierThinkNode.EvidenceVerification evidenceVerification) {
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("status", status);
        verification.put("liveEvidenceCount", liveEvidenceCount);
        verification.put(
                "evidenceRefs",
                evidence.stream()
                        .filter(EvidenceItem::succeeded)
                        .map(EvidenceItem::evidenceId)
                        .toList());
        verification.put(
                "sources",
                evidence.stream()
                        .filter(EvidenceItem::succeeded)
                        .map(EvidenceItem::source)
                        .distinct()
                        .toList());
        verification.put("conflictCount", evidenceVerification.conflictCount());
        verification.put("stateCompareAvailable", baselineAvailable);
        verification.put("executorSucceeded", executorSucceeded);
        verification.put("conclusionEvidenceStatus", evidenceVerification.status());
        verification.put("conclusionEvidenceReason", evidenceVerification.reason());
        verification.put("matchedEvidenceRefs", evidenceVerification.matchedEvidenceRefs());
        verification.put("missingEvidenceRefs", evidenceVerification.missingEvidenceRefs());
        put(verification, "verifierDecision", state.getContext().get("verifierDecision"));
        if (!conclusions.isEmpty()) {
            AiConclusion conclusion = conclusions.get(0);
            verification.put("conclusionStatus", conclusion.status());
            verification.put("conclusion", conclusion.claim());
            verification.put("confidence", conclusion.confidence());
        } else if (state.getContext().get("evidenceConfidence") instanceof ConfidenceAssessment confidence) {
            verification.put("confidence", confidence);
        }
        return Map.copyOf(verification);
    }

    private List<String> invokedTools(GraphState state, List<EvidenceItem> evidence) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        Map<String, Object> knowledge = map(state.getContext().get("plannerKnowledge"));
        for (Object value : knowledge.values()) {
            if (!(value instanceof Map<?, ?> item) || item.get("tool") == null) {
                continue;
            }
            if (!"FORBIDDEN".equalsIgnoreCase(String.valueOf(item.get("collectionStatus")))) {
                tools.add(String.valueOf(item.get("tool")));
            }
        }
        evidence.stream()
                .filter(EvidenceItem::succeeded)
                .map(this::evidenceTool)
                .forEach(tools::add);
        if (successfulToolResult(state.getContext().get("executorResult"))) {
            putTool(tools, selectedTool(state));
        }
        return tools.stream()
                .filter(tool -> tool != null && !tool.isBlank())
                .limit(16)
                .toList();
    }

    private String evidenceTool(EvidenceItem item) {
        if (item.type() == EvidenceType.METRIC && item.source().contains("prometheus")) {
            return "prometheus.rangeQuery";
        }
        if (item.type() == EvidenceType.RESOURCE_STATE && item.source().contains("kubernetes")) {
            return "kubernetes.describeResource";
        }
        if (item.type() == EvidenceType.K8S_EVENT) {
            return "kubernetes.queryEvents";
        }
        if (item.type() == EvidenceType.POD_LOG && item.source().contains("kubernetes")) {
            return "kubernetes.queryPodLogs";
        }
        if (item.type() == EvidenceType.POD_LOG && item.source().contains("loki")) {
            return "loki.queryRange";
        }
        if (item.type() == EvidenceType.ALERT) {
            return "alerts.getActiveAlerts";
        }
        if (item.type() == EvidenceType.SOP) {
            return "knowledge.searchSop";
        }
        return item.source();
    }

    private String strategy(String verificationStatus, boolean plannerDegraded, String blockedReason) {
        if ("VERIFIED".equals(verificationStatus) && plannerDegraded) {
            return "SKILL_RULE_FALLBACK_VERIFIED";
        }
        if ("VERIFIED".equals(verificationStatus)) {
            return "SKILL_AGENT_VERIFIED";
        }
        if (blockedReason != null) {
            return "SKILL_DETERMINISTIC_RUNBOOK_FALLBACK";
        }
        return "SKILL_AGENT_PARTIAL_FALLBACK";
    }

    private Map<String, Object> plannerKnowledge(AlertWorkflowContext context) {
        NormalizedAlarmEvent event = context.getNormalizedAlarm();
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        Map<String, Object> alarm = new LinkedHashMap<>();
        put(alarm, "alertName", event == null ? null : event.alertName());
        put(
                alarm,
                "resourceType",
                event == null || event.resourceType() == null
                        ? null
                        : event.resourceType().name());
        put(alarm, "resourceName", event == null ? null : event.resourceName());
        put(alarm, "cluster", event == null ? null : event.cluster());
        put(alarm, "namespace", event == null ? null : event.namespace());
        put(alarm, "service", event == null ? null : event.service());
        put(alarm, "metricName", event == null ? null : event.metricName());
        put(alarm, "summary", event == null ? null : event.summary());
        put(alarm, "runbookId", runbookId(context));

        Map<String, Object> knowledge = new LinkedHashMap<>();
        knowledge.put("alarm", Map.copyOf(alarm));
        knowledge.put(
                "workflowEvidence",
                Map.of(
                        "stateCompareAvailable",
                        successfulToolResult(context.getAttribute("stateCompareResult")),
                        "knowledgeAvailable",
                        context.getAttribute("knowledgeHints") != null));
        if (evaluation != null) {
            Map<String, Object> policy = new LinkedHashMap<>();
            put(policy, "policyId", evaluation.policyId());
            put(policy, "promql", evaluation.promql());
            put(policy, "window", evaluation.window());
            put(policy, "runbookId", evaluation.runbookId());
            put(policy, "workflowTemplate", evaluation.workflowTemplate());
            knowledge.put("policy", Map.copyOf(policy));
        }
        return knowledge;
    }

    private Map<String, Object> requestScope(NormalizedAlarmEvent event) {
        if (event == null) {
            return Map.of();
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        put(scope, "cluster", event.cluster());
        put(scope, "namespace", event.namespace());
        put(
                scope,
                "resourceKind",
                event.resourceType() == null ? null : event.resourceType().name());
        put(scope, "resourceName", event.resourceName());
        put(
                scope,
                "resourceUid",
                first(
                        event.labels().get("resource_uid"),
                        event.labels().get("uid"),
                        event.metadata().get("resourceUid")));
        return Map.copyOf(scope);
    }

    private String diagnosticRequest(NormalizedAlarmEvent event, String runbookId) {
        if (event == null) {
            return "Read-only diagnose the current Kubernetes alert using live evidence. Do not execute any mutation.";
        }
        return String.join(
                " ",
                "Read-only diagnose Kubernetes alert",
                safe(event.alertName()),
                "for",
                event.resourceType() == null ? "resource" : event.resourceType().name(),
                safe(event.resourceName()),
                "in namespace",
                safe(event.namespace()),
                "cluster",
                safe(event.cluster()) + ".",
                "Query current metrics, resource state, events, logs, and bound runbook",
                safe(runbookId) + ".",
                "Do not execute any mutation.");
    }

    private String runbookId(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null
                && evaluation.runbookId() != null
                && !evaluation.runbookId().isBlank()) {
            return evaluation.runbookId();
        }
        return context.getNormalizedAlarm() == null
                ? null
                : context.getNormalizedAlarm().runbookId();
    }

    private String agentExecutionId(AlertWorkflowContext context) {
        NormalizedAlarmEvent event = context.getNormalizedAlarm();
        String identity = (event == null ? "" : safe(event.fingerprint())) + "|" + context.getStartedAt();
        return "agd_" + sha256(identity).substring(0, 32);
    }

    private String selectedTool(GraphState state) {
        Map<String, Object> payload = map(state.getContext().get("executorPayload"));
        Object toolName = payload.get("toolName");
        return toolName == null ? null : String.valueOf(toolName);
    }

    private boolean successfulToolResult(Object value) {
        if (!(value instanceof Map<?, ?> result)) {
            return false;
        }
        Object status = result.get("status");
        if (status != null) {
            return "success".equalsIgnoreCase(String.valueOf(status))
                    && (!(result.get("httpStatus") instanceof Number number) || number.intValue() < 400);
        }
        Object httpStatus = result.get("httpStatus");
        return httpStatus instanceof Number number && number.intValue() < 400;
    }

    private static <T> List<T> typed(Object value, Class<T> type) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private static void putTool(LinkedHashSet<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private static void copyScalar(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    private static Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record Outcome(
            boolean attempted,
            String strategy,
            boolean verified,
            Map<String, Object> plan,
            Map<String, Object> verification,
            Map<String, Object> fallback,
            List<String> invokedTools,
            List<EvidenceItem> evidenceItems,
            List<AiConclusion> conclusions,
            String agentExecutionId) {

        public Outcome {
            strategy = safe(strategy);
            plan = plan == null ? Map.of() : Map.copyOf(plan);
            verification = verification == null ? Map.of() : Map.copyOf(verification);
            fallback = fallback == null ? Map.of() : Map.copyOf(fallback);
            invokedTools = invokedTools == null ? List.of() : List.copyOf(invokedTools);
            evidenceItems = evidenceItems == null ? List.of() : List.copyOf(evidenceItems);
            conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
            agentExecutionId = safe(agentExecutionId);
        }

        public static Outcome notAttempted() {
            return new Outcome(false, "", false, Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), "");
        }
    }
}
