package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ConversationHistoryCompactor {

    private static final List<String> FACT_SIGNALS = List.of(
            "记住", "负责人", "必须", "不要", "环境", "集群", "端口", "版本",
            "owner", "namespace", "cluster", "service", "environment", "port", "version",
            "always", "never", "="
    );

    private final KubeOnCallProperties properties;
    private final TokenBudget tokenBudget;

    public ConversationHistoryCompactor(KubeOnCallProperties properties, TokenBudget tokenBudget) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
    }

    public SessionSnapshot append(SessionSnapshot current, SessionTurn newTurn) {
        SessionSnapshot base = current == null
                ? new SessionSnapshot("", List.of(), null, null)
                : current;
        List<SessionTurn> allTurns = new ArrayList<>(base.turns().stream().map(this::boundedTurn).toList());
        if (newTurn != null) {
            allTurns.add(boundedTurn(newTurn));
        }
        int maxTurns = Math.max(1, properties.getMemory().getMaxSessionTurns());
        int recentLimit = Math.max(1, Math.min(maxTurns, properties.getMemory().getSessionRecentTurns()));
        int storageBudget = Math.max(64, properties.getMemory().getSessionTokenBudget());
        boolean alreadyCompacted = base.compactedTurnCount() > 0 || !base.summary().isBlank();
        int activeTurnLimit = alreadyCompacted ? recentLimit : maxTurns;
        if (allTurns.size() <= activeTurnLimit
                && estimateSnapshotTokens(base.summary(), allTurns) <= storageBudget) {
            return new SessionSnapshot(
                    base.sessionId(), allTurns, base.createdAt(), Instant.now(),
                    base.summary(), base.compactedTurnCount());
        }

        int keepCount = Math.min(recentLimit, allTurns.size());
        while (keepCount > 1) {
            List<SessionTurn> recent = allTurns.subList(allTurns.size() - keepCount, allTurns.size());
            if (estimateSnapshotTokens(base.summary(), recent) <= storageBudget) {
                break;
            }
            keepCount--;
        }
        int compactCount = Math.max(0, allTurns.size() - keepCount);
        List<SessionTurn> compacted = allTurns.subList(0, compactCount);
        List<SessionTurn> recent = List.copyOf(allTurns.subList(compactCount, allTurns.size()));
        String summary = compacted.isEmpty()
                ? base.summary()
                : summarize(base.summary(), compacted, base.compactedTurnCount() + compacted.size());
        return new SessionSnapshot(
                base.sessionId(), recent, base.createdAt(), Instant.now(),
                summary, base.compactedTurnCount() + compacted.size());
    }

    public String buildContext(SessionSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (!snapshot.summary().isBlank()) {
            builder.append(snapshot.summary());
        }
        int recentLimit = Math.max(1, properties.getMemory().getSessionRecentTurns());
        List<SessionTurn> turns = snapshot.turns();
        List<SessionTurn> recent = turns.size() > recentLimit
                ? turns.subList(turns.size() - recentLimit, turns.size())
                : turns;
        for (SessionTurn turn : recent) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("Previous user: ").append(turn.question())
                    .append("\nPrevious assistant status: ").append(nonNull(turn.status()))
                    .append("\nPrevious assistant summary: ").append(turn.answer());
        }
        return tokenBudget.compactText(builder.toString(), properties.getMemory().getSessionTokenBudget());
    }

    private SessionTurn boundedTurn(SessionTurn turn) {
        int sessionBudget = Math.max(64, properties.getMemory().getSessionTokenBudget());
        int questionBudget = Math.max(16, Math.min(160, sessionBudget / 6));
        int answerBudget = Math.max(32, Math.min(320, sessionBudget / 3));
        return new SessionTurn(
                turn.executionId(),
                tokenBudget.compactText(turn.question(), questionBudget),
                tokenBudget.compactText(turn.answer(), answerBudget),
                turn.status(),
                turn.createdAt());
    }

    private String summarize(String existingSummary, List<SessionTurn> turns, int compactedCount) {
        Set<String> facts = new LinkedHashSet<>();
        if (existingSummary != null && !existingSummary.isBlank()) {
            existingSummary.lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("- "))
                    .map(line -> line.substring(2))
                    .filter(this::looksLikeFact)
                    .forEach(facts::add);
        }
        for (SessionTurn turn : turns) {
            String combined = normalized(turn.question()) + " -> " + normalized(turn.answer());
            if (looksLikeFact(combined)) {
                facts.add(tokenBudget.compactText(combined, 120));
            }
        }

        StringBuilder builder = new StringBuilder("Compacted session history (")
                .append(compactedCount)
                .append(" turns)");
        if (!facts.isEmpty()) {
            builder.append("\nKey facts:");
            facts.stream().limit(12).forEach(fact -> builder.append("\n- ").append(fact));
        }
        builder.append("\nRecent compacted turns:");
        int start = Math.max(0, turns.size() - 6);
        for (SessionTurn turn : turns.subList(start, turns.size())) {
            builder.append("\n- user=")
                    .append(tokenBudget.compactText(turn.question(), 60))
                    .append("; assistant=")
                    .append(tokenBudget.compactText(turn.answer(), 90));
        }
        return tokenBudget.compactText(
                builder.toString(),
                Math.max(64, properties.getMemory().getSessionSummaryTokenBudget()));
    }

    private int estimateSnapshotTokens(String summary, List<SessionTurn> turns) {
        int tokens = tokenBudget.estimateTokens(summary);
        for (SessionTurn turn : turns) {
            tokens += tokenBudget.estimateTokens(turn.question());
            tokens += tokenBudget.estimateTokens(turn.answer());
        }
        return tokens;
    }

    private boolean looksLikeFact(String value) {
        String normalized = nonNull(value).toLowerCase(Locale.ROOT);
        return FACT_SIGNALS.stream().anyMatch(normalized::contains);
    }

    private String normalized(String value) {
        return nonNull(value).replaceAll("\\s+", " ").trim();
    }

    private String nonNull(String value) {
        return value == null ? "" : value;
    }
}
