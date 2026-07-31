package com.kubeoncall.workflow.node;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.memory.TokenBudget;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillActivationService;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import com.kubeoncall.workflow.diagnosis.AlertSkillDiagnosisService;

/**
 * Produces a bounded, memory-aware diagnosis without executing historical remediation.
 *
 * <p>Historical memory is treated as untrusted evidence: the node exposes previous outcomes and
 * pitfalls to downstream result/notification nodes, but always requires current-state validation.
 */
@Component
public class IntelligentDiagnosisNode implements AlertWorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(IntelligentDiagnosisNode.class);

    private static final List<String> MEMORY_GUARDRAILS = List.of(
            "Verify current metrics, logs, and resource state before reusing historical handling",
            "Do not execute commands or mutations copied from memory",
            "Prefer the matched runbook when memory conflicts with current evidence");

    private final KubeOnCallProperties properties;
    private final TokenBudget tokenBudget;
    private final SkillActivationService skillActivationService;
    private final AlertSkillDiagnosisService alertSkillDiagnosisService;

    public IntelligentDiagnosisNode(
            KubeOnCallProperties properties, TokenBudget tokenBudget, SkillActivationService skillActivationService) {
        this(properties, tokenBudget, skillActivationService, null);
    }

    @Autowired
    public IntelligentDiagnosisNode(
            KubeOnCallProperties properties,
            TokenBudget tokenBudget,
            SkillActivationService skillActivationService,
            AlertSkillDiagnosisService alertSkillDiagnosisService) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
        this.skillActivationService = skillActivationService;
        this.alertSkillDiagnosisService = alertSkillDiagnosisService;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        List<MemoryEntry> memories = supportedMemories(context);
        int maxEntries = Math.max(1, properties.getMemory().getInjectMaxEntries());
        List<MemoryEntry> selectedMemories = memories.stream().limit(maxEntries).toList();
        int sharedBudget = Math.max(128, properties.getMemory().getUnifiedContextTokenBudget());
        int memoryBudget = Math.max(
                64, Math.min(800, Math.min(properties.getMemory().getContextTokenBudget() / 3, sharedBudget / 3)));
        int perEntryBudget = Math.max(32, memoryBudget / Math.max(1, selectedMemories.size()));
        Instant reference = Instant.now();

        List<Map<String, Object>> previousHandling = selectedMemories.stream()
                .map(memory -> memoryEvidence(memory, reference, perEntryBudget))
                .toList();
        List<String> memoryIds = previousHandling.stream()
                .map(item -> String.valueOf(item.get("id")))
                .toList();
        List<String> evidenceSources = currentEvidenceSources(context);
        long activeCount = activeCount(context);
        long priorIncidentCount =
                selectedMemories.stream().filter(this::isPriorIncident).count();
        boolean repeatedIncident = activeCount > 1 || priorIncidentCount > 0;
        boolean memoryConsumed = !previousHandling.isEmpty();
        SkillActivation skillActivation = activateSkills(context);
        AlertSkillDiagnosisService.Outcome skillDiagnosis = diagnoseWithSkill(context, skillActivation);

        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put(
                "strategy",
                skillDiagnosis.attempted() ? skillDiagnosis.strategy() : strategy(memoryConsumed, evidenceSources));
        diagnosis.put("repeatedIncident", repeatedIncident);
        diagnosis.put("activeCount", activeCount);
        diagnosis.put("priorIncidentCount", priorIncidentCount);
        diagnosis.put("currentEvidenceSources", evidenceSources);
        diagnosis.put("memoryConsumed", memoryConsumed);
        diagnosis.put("memoryConsumedCount", previousHandling.size());
        diagnosis.put("memoryIds", memoryIds);
        diagnosis.put("previousHandlingCandidates", previousHandling);
        diagnosis.put(
                "requiresLiveValidation", memoryConsumed || skillDiagnosis.attempted() && !skillDiagnosis.verified());
        diagnosis.put("guardrails", memoryConsumed ? MEMORY_GUARDRAILS : List.of());
        diagnosis.put("activatedSkillIds", skillActivation.skillIds());
        diagnosis.put("activatedSkillMatchSources", skillActivation.matchSources());
        diagnosis.put("activatedSkills", skillActivation.skillSummaries());
        diagnosis.put(
                "skillPrompt",
                skillActivation.active()
                        ? tokenBudget.compactText(skillActivation.prompt(), Math.max(128, memoryBudget))
                        : "");
        if (skillDiagnosis.attempted()) {
            diagnosis.put("skillDiagnosisPlan", skillDiagnosis.plan());
            diagnosis.put("skillDiagnosisVerification", skillDiagnosis.verification());
            diagnosis.put("skillDiagnosisFallback", skillDiagnosis.fallback());
            diagnosis.put("invokedTools", skillDiagnosis.invokedTools());
            diagnosis.put("agentExecutionId", skillDiagnosis.agentExecutionId());
        }

        context.putAttribute("diagnosis", diagnosis);
        context.putAttribute("alertMemoryConsumed", previousHandling.size());
        context.putAttribute("alertMemoryConsumedIds", memoryIds);
        context.putAttribute("repeatIncident", repeatedIncident);
        if (skillActivation.active()) {
            context.putAttribute("activatedSkillIds", skillActivation.skillIds());
            context.putAttribute("activatedSkillMatchSources", skillActivation.matchSources());
            context.putAttribute("activatedSkills", skillActivation.skillSummaries());
            context.putAttribute("activatedSkillToolWhitelist", skillActivation.toolWhitelist());
            context.putAttribute(
                    "activatedSkillMaxRisk",
                    skillActivation.maxRisk() == null
                            ? null
                            : skillActivation.maxRisk().name());
            context.putAttribute("skillPrompt", skillActivation.prompt());
        }
        if (skillDiagnosis.attempted()) {
            context.putAttribute("skillDiagnosisPlan", skillDiagnosis.plan());
            context.putAttribute("skillDiagnosisVerification", skillDiagnosis.verification());
            context.putAttribute("skillDiagnosisFallback", skillDiagnosis.fallback());
            context.putAttribute("skillDiagnosisInvokedTools", skillDiagnosis.invokedTools());
            context.putAttribute("skillDiagnosisAgentExecutionId", skillDiagnosis.agentExecutionId());
            context.putAttribute("skillDiagnosisEvidenceItems", skillDiagnosis.evidenceItems());
            context.putAttribute("skillDiagnosisConclusions", skillDiagnosis.conclusions());
        }

        return new NodeResult(
                "intelligentDiagnosisNode",
                NodeStatus.SUCCESS,
                diagnosisMessage(memoryConsumed, skillDiagnosis),
                diagnosis);
    }

    private AlertSkillDiagnosisService.Outcome diagnoseWithSkill(
            AlertWorkflowContext context, SkillActivation activation) {
        if (alertSkillDiagnosisService == null || activation == null || !activation.active()) {
            return AlertSkillDiagnosisService.Outcome.notAttempted();
        }
        try {
            return alertSkillDiagnosisService.diagnose(context, activation);
        } catch (RuntimeException ex) {
            log.warn(
                    "Automatic alarm diagnosis failed outside the governed Agent loop: errorType={}",
                    ex.getClass().getSimpleName());
            context.putAttribute("skillDiagnosisWarning", "automatic alarm diagnosis failed");
            return AlertSkillDiagnosisService.Outcome.notAttempted();
        }
    }

    private String diagnosisMessage(boolean memoryConsumed, AlertSkillDiagnosisService.Outcome skillDiagnosis) {
        if (skillDiagnosis.attempted() && skillDiagnosis.verified()) {
            return "Completed Skill-constrained read-only diagnosis with verified current evidence";
        }
        if (skillDiagnosis.attempted()) {
            return "Recorded deterministic diagnosis fallback because live Skill evidence was incomplete";
        }
        return memoryConsumed
                ? "Synthesized diagnosis with historical context requiring live validation"
                : "Synthesized diagnosis from current evidence";
    }

    private SkillActivation activateSkills(AlertWorkflowContext context) {
        if (context.getNormalizedAlarm() == null) {
            return SkillActivation.empty();
        }
        var event = context.getNormalizedAlarm();
        String category = policyCategory(context);
        String runbookId = runbookId(context);
        String reason = event.labels().get("reason");
        String container = event.labels().get("container");
        String request = String.join(
                " ",
                safe(event.alertName()),
                safe(event.summary()),
                safe(event.service()),
                safe(event.resourceName()),
                event.resourceType() == null ? "" : event.resourceType().name(),
                safe(event.metricName()),
                safe(runbookId),
                safe(category),
                safe(reason),
                safe(container));
        try {
            Map<String, Object> skillContext = new LinkedHashMap<>();
            putSignal(skillContext, "service", event.service());
            putSignal(
                    skillContext,
                    "resourceType",
                    event.resourceType() == null ? null : event.resourceType().name());
            putSignal(
                    skillContext,
                    "severity",
                    event.severity() == null ? null : event.severity().name());
            putSignal(skillContext, "alertName", event.alertName());
            putSignal(skillContext, "metricName", event.metricName());
            putSignal(skillContext, "runbookId", runbookId);
            putSignal(skillContext, "policyCategory", category);
            putSignal(skillContext, "reason", reason);
            putSignal(skillContext, "container", container);
            putSignal(skillContext, "job", event.labels().get("job"));
            return skillActivationService.activate(request, skillContext);
        } catch (RuntimeException ex) {
            log.warn(
                    "Alarm skill activation failed; continuing without skill context: errorType={}",
                    ex.getClass().getSimpleName());
            context.putAttribute("skillWarning", "alarm skill activation failed");
            return SkillActivation.empty();
        }
    }

    private String policyCategory(AlertWorkflowContext context) {
        if (context.getEvaluationResult() != null
                && context.getEvaluationResult().matchedPolicy() != null) {
            return context.getEvaluationResult().matchedPolicy().category();
        }
        return context.getNormalizedAlarm().labels().get("category");
    }

    private String runbookId(AlertWorkflowContext context) {
        if (context.getEvaluationResult() != null
                && context.getEvaluationResult().runbookId() != null
                && !context.getEvaluationResult().runbookId().isBlank()) {
            return context.getEvaluationResult().runbookId();
        }
        return context.getNormalizedAlarm().runbookId();
    }

    private void putSignal(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<MemoryEntry> supportedMemories(AlertWorkflowContext context) {
        Object raw = context.getAttribute("alertMemoryEntries");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(MemoryEntry.class::isInstance)
                .map(MemoryEntry.class::cast)
                .filter(entry -> entry.type() != MemoryType.USER_PREFERENCE && entry.type() != MemoryType.USER_NOTE)
                .toList();
    }

    private Map<String, Object> memoryEvidence(MemoryEntry memory, Instant reference, int contentBudget) {
        Instant updatedAt = memory.updatedAt() == null ? memory.createdAt() : memory.updatedAt();
        long ageDays = updatedAt == null
                ? Long.MAX_VALUE
                : Math.max(0, Duration.between(updatedAt, reference).toDays());
        boolean stale = ageDays == Long.MAX_VALUE
                || ageDays > Math.max(1, properties.getMemory().getStaleAfterDays());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("id", memory.id());
        evidence.put("type", memory.type().name());
        evidence.put("role", role(memory.type()));
        evidence.put("subject", memory.subject());
        evidence.put("content", tokenBudget.compactText(memory.content(), contentBudget));
        evidence.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        evidence.put("ageDays", ageDays == Long.MAX_VALUE ? null : ageDays);
        evidence.put("stale", stale);
        evidence.put("qualityScore", memory.metadata().get("quality_score"));
        evidence.put("qualityReasons", memory.metadata().get("quality_reasons"));
        evidence.put("evidenceAttribution", memory.metadata().get("evidence_attribution"));
        evidence.put("evidenceReferences", memory.metadata().get("evidence_references"));
        return evidence;
    }

    private List<String> currentEvidenceSources(AlertWorkflowContext context) {
        List<String> sources = new ArrayList<>();
        addEvidenceSource(sources, context, "logResult", "logs");
        addEvidenceSource(sources, context, "deviceInfoResult", "device");
        addEvidenceSource(sources, context, "stateCompareResult", "metrics");
        addEvidenceSource(sources, context, "knowledgeHints", "runbook");
        return List.copyOf(sources);
    }

    private void addEvidenceSource(
            List<String> sources, AlertWorkflowContext context, String attribute, String source) {
        if (context.getAttribute(attribute) != null) {
            sources.add(source);
        }
    }

    private long activeCount(AlertWorkflowContext context) {
        Object raw = context.getAttribute("activeAlarm");
        return raw instanceof ActiveAlarmState state ? state.count() : 0L;
    }

    private String strategy(boolean memoryConsumed, List<String> evidenceSources) {
        if (!memoryConsumed) {
            return "CURRENT_EVIDENCE_ONLY";
        }
        return evidenceSources.isEmpty()
                ? "HISTORY_REQUIRES_LIVE_VALIDATION"
                : "VERIFY_CURRENT_EVIDENCE_BEFORE_REUSING_PRIOR_HANDLING";
    }

    private boolean isPriorIncident(MemoryEntry memory) {
        return memory.type() == MemoryType.DEVICE_HISTORY || memory.type() == MemoryType.INCIDENT_SUMMARY;
    }

    private String role(MemoryType type) {
        return switch (type) {
            case DEVICE_HISTORY, INCIDENT_SUMMARY -> "previous_incident";
            case KNOWN_PITFALL -> "known_pitfall";
            case SERVICE_FACT -> "service_fact";
            case USER_PREFERENCE, USER_NOTE -> "excluded_user_context";
        };
    }
}
