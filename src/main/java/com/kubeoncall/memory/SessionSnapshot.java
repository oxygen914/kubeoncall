package com.kubeoncall.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record SessionSnapshot(
        String sessionId,
        List<SessionTurn> turns,
        Instant createdAt,
        Instant updatedAt,
        String summary,
        int compactedTurnCount) {
    public SessionSnapshot {
        turns = turns == null ? List.of() : List.copyOf(turns);
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        summary = summary == null ? "" : summary;
        compactedTurnCount = Math.max(0, compactedTurnCount);
    }

    public SessionSnapshot(String sessionId, List<SessionTurn> turns, Instant createdAt, Instant updatedAt) {
        this(sessionId, turns, createdAt, updatedAt, "", 0);
    }

    public SessionSnapshot append(SessionTurn turn, int maxTurns) {
        List<SessionTurn> nextTurns = new ArrayList<>(turns);
        nextTurns.add(turn);
        int limit = Math.max(1, maxTurns);
        if (nextTurns.size() > limit) {
            nextTurns = nextTurns.subList(nextTurns.size() - limit, nextTurns.size());
        }
        return new SessionSnapshot(sessionId, nextTurns, createdAt, Instant.now(), summary, compactedTurnCount);
    }
}
