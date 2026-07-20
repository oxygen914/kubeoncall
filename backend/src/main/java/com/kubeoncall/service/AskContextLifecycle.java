package com.kubeoncall.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.memory.ContextCompressor;
import com.kubeoncall.memory.ConversationHistoryCompactor;
import com.kubeoncall.memory.MemoryExtractor;
import com.kubeoncall.memory.MemoryInjection;
import com.kubeoncall.memory.MemoryInjector;
import com.kubeoncall.memory.SessionSnapshot;
import com.kubeoncall.memory.SessionStore;
import com.kubeoncall.memory.SessionTurn;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillActivationService;

@Component
public class AskContextLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AskContextLifecycle.class);

    private final SessionStore sessionStore;
    private final MemoryInjector memoryInjector;
    private final MemoryExtractor memoryExtractor;
    private final SkillActivationService skillActivationService;
    private final ContextCompressor contextCompressor;
    private final ConversationHistoryCompactor historyCompactor;

    public AskContextLifecycle(
            SessionStore sessionStore,
            MemoryInjector memoryInjector,
            MemoryExtractor memoryExtractor,
            SkillActivationService skillActivationService,
            ContextCompressor contextCompressor,
            ConversationHistoryCompactor historyCompactor) {
        this.sessionStore = sessionStore;
        this.memoryInjector = memoryInjector;
        this.memoryExtractor = memoryExtractor;
        this.skillActivationService = skillActivationService;
        this.contextCompressor = contextCompressor;
        this.historyCompactor = historyCompactor;
    }

    public String prepare(GraphState state, String question, String requestedSessionId) {
        String sessionId = normalizeSessionId(requestedSessionId);
        attachSessionContext(state, sessionId);
        attachMemoryContext(state, question);
        attachSkillContext(state, question);
        contextCompressor.compressPlanningContext(state);
        return sessionId;
    }

    public void complete(String sessionId, GraphState state, String message) {
        appendSessionTurn(sessionId, state, message);
        extractMemory(state, message);
    }

    public void compressRuntime(GraphState state) {
        contextCompressor.compressRuntime(state);
    }

    public String sessionId(GraphState state) {
        Object value = state.getContext().get("sessionId");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private void attachSessionContext(GraphState state, String sessionId) {
        if (sessionId == null) {
            return;
        }
        state.getContext().put("sessionId", sessionId);
        try {
            SessionSnapshot snapshot = sessionStore.find(sessionId).orElse(null);
            if (snapshot == null || snapshot.turns().isEmpty()) {
                state.getContext().put("sessionHistory", List.of());
                state.getContext().put("sessionContext", "");
                return;
            }
            List<Map<String, String>> history =
                    snapshot.turns().stream().map(this::toSessionHistoryMap).toList();
            state.getContext().put("sessionHistory", history);
            state.getContext().put("sessionSummary", snapshot.summary());
            state.getContext().put("sessionCompactedTurnCount", snapshot.compactedTurnCount());
            state.getContext().put("sessionContext", historyCompactor.buildContext(snapshot));
        } catch (RuntimeException ex) {
            log.warn(
                    "Session history lookup failed; continuing without history: errorType={}",
                    ex.getClass().getSimpleName());
            state.getContext().put("sessionHistory", List.of());
            state.getContext().put("sessionContext", "");
            state.getContext().put("sessionWarning", "session history unavailable");
        }
    }

    private Map<String, String> toSessionHistoryMap(SessionTurn turn) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("executionId", defaultString(turn.executionId()));
        map.put("question", abbreviate(turn.question(), 300));
        map.put("answer", abbreviate(turn.answer(), 500));
        map.put("status", defaultString(turn.status()));
        map.put("createdAt", turn.createdAt() == null ? "" : turn.createdAt().toString());
        return map;
    }

    private void attachMemoryContext(GraphState state, String question) {
        try {
            MemoryInjection injection = memoryInjector.inject(question, Map.of());
            if (!injection.prompt().isBlank()) {
                state.getContext().put("memoryContext", injection.prompt());
                state.getContext()
                        .put("injectedMemoryCount", injection.entries().size());
            }
            if (!injection.warning().isBlank()) {
                state.getContext().put("memoryWarning", injection.warning());
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Memory injection failed; continuing without memory: errorType={}",
                    ex.getClass().getSimpleName());
            state.getContext().put("memoryWarning", "memory injection failed");
        }
    }

    private void attachSkillContext(GraphState state, String question) {
        try {
            SkillActivation activation = skillActivationService.activate(question, state.getContext());
            if (!activation.active()) {
                state.getContext().put("activatedSkills", List.of());
                return;
            }
            state.getContext().put("activatedSkills", activation.skillSummaries());
            state.getContext().put("activatedSkillIds", activation.skillIds());
            state.getContext().put("skillPrompt", activation.prompt());
            state.getContext().put("activatedSkillToolWhitelist", activation.toolWhitelist());
            if (activation.maxRisk() != null) {
                state.getContext()
                        .put("activatedSkillMaxRisk", activation.maxRisk().name());
            }
            mergePlannerSkillKnowledge(state, activation);
        } catch (RuntimeException ex) {
            log.warn(
                    "Skill activation failed; continuing without skills: errorType={}",
                    ex.getClass().getSimpleName());
            state.getContext().put("skillWarning", "skill activation failed");
        }
    }

    private void mergePlannerSkillKnowledge(GraphState state, SkillActivation activation) {
        Object value = state.getContext().get("plannerKnowledge");
        Map<String, Object> plannerKnowledge = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, entryValue) -> plannerKnowledge.put(String.valueOf(key), entryValue));
        }
        plannerKnowledge.put("activatedSkills", activation.skillSummaries());
        plannerKnowledge.put("activatedSkillIds", activation.skillIds());
        plannerKnowledge.put("activatedSkillToolWhitelist", activation.toolWhitelist());
        plannerKnowledge.put(
                "activatedSkillMaxRisk",
                activation.maxRisk() == null ? null : activation.maxRisk().name());
        plannerKnowledge.put("skillPrompt", activation.prompt());
        state.getContext().put("plannerKnowledge", plannerKnowledge);
    }

    private void appendSessionTurn(String sessionId, GraphState state, String message) {
        if (sessionId == null) {
            return;
        }
        try {
            sessionStore.append(
                    sessionId,
                    new SessionTurn(
                            state.getExecutionId(),
                            state.getUserRequest(),
                            message,
                            state.getStatus() == null ? null : state.getStatus().name(),
                            Instant.now()));
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to append session turn: sessionId={}, executionId={}, errorType={}",
                    sessionId,
                    state.getExecutionId(),
                    ex.getClass().getSimpleName());
        }
    }

    private void extractMemory(GraphState state, String message) {
        try {
            memoryExtractor.extractFromAsk(state, message);
        } catch (RuntimeException ex) {
            log.warn(
                    "Memory extraction failed; continuing workflow completion: errorType={}",
                    ex.getClass().getSimpleName());
            state.getContext().put("memoryExtractionWarning", "memory extraction failed");
        }
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = defaultString(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
