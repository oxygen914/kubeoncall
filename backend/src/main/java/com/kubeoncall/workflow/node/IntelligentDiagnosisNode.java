package com.kubeoncall.workflow.node;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public IntelligentDiagnosisNode(
            KubeOnCallProperties properties, TokenBudget tokenBudget, SkillActivationService skillActivationService) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
        this.skillActivationService = skillActivationService;
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

        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("strategy", strategy(memoryConsumed, evidenceSources));
        diagnosis.put("repeatedIncident", repeatedIncident);
        diagnosis.put("activeCount", activeCount);
        diagnosis.put("priorIncidentCount", priorIncidentCount);
        diagnosis.put("currentEvidenceSources", evidenceSources);
        diagnosis.put("memoryConsumed", memoryConsumed);
        diagnosis.put("memoryConsumedCount", previousHandling.size());
        diagnosis.put("memoryIds", memoryIds);
        diagnosis.put("previousHandlingCandidates", previousHandling);
        diagnosis.put("requiresLiveValidation", memoryConsumed);
        diagnosis.put("guardrails", memoryConsumed ? MEMORY_GUARDRAILS : List.of());
        diagnosis.put("activatedSkillIds", skillActivation.skillIds());
        diagnosis.put("activatedSkills", skillActivation.skillSummaries());
        diagnosis.put(
                "skillPrompt",
                skillActivation.active()
                        ? tokenBudget.compactText(skillActivation.prompt(), Math.max(128, memoryBudget))
                        : "");

        context.putAttribute("diagnosis", diagnosis);
        context.putAttribute("alertMemoryConsumed", previousHandling.size());
        context.putAttribute("alertMemoryConsumedIds", memoryIds);
        context.putAttribute("repeatIncident", repeatedIncident);
        if (skillActivation.active()) {
            context.putAttribute("activatedSkillIds", skillActivation.skillIds());
            context.putAttribute("activatedSkills", skillActivation.skillSummaries());
            context.putAttribute("activatedSkillToolWhitelist", skillActivation.toolWhitelist());
            context.putAttribute(
                    "activatedSkillMaxRisk",
                    skillActivation.maxRisk() == null
                            ? null
                            : skillActivation.maxRisk().name());
            context.putAttribute("skillPrompt", skillActivation.prompt());
        }

        return new NodeResult(
                "intelligentDiagnosisNode",
                NodeStatus.SUCCESS,
                memoryConsumed
                        ? "Synthesized diagnosis with historical context requiring live validation"
                        : "Synthesized diagnosis from current evidence",
                diagnosis);
    }

    private SkillActivation activateSkills(AlertWorkflowContext context) {
        if (context.getNormalizedAlarm() == null) {
            return SkillActivation.empty();
        }
        var event = context.getNormalizedAlarm();
        String request = String.join(
                " ",
                safe(event.alertName()),
                safe(event.summary()),
                safe(event.service()),
                safe(event.resourceName()),
                event.resourceType() == null ? "" : event.resourceType().name());
        try {
            return skillActivationService.activate(
                    request,
                    Map.of(
                            "service", safe(event.service()),
                            "resourceType",
                                    event.resourceType() == null
                                            ? ""
                                            : event.resourceType().name(),
                            "severity",
                                    event.severity() == null
                                            ? ""
                                            : event.severity().name()));
        } catch (RuntimeException ex) {
            log.warn(
                    "Alarm skill activation failed; continuing without skill context: errorType={}",
                    ex.getClass().getSimpleName());
            context.putAttribute("skillWarning", "alarm skill activation failed");
            return SkillActivation.empty();
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
