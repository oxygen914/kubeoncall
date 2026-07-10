package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationHistoryCompactorTest {

    @Test
    void shouldCompactFiftyTurnsAndPreserveEarlyKeyFactAndRecentContext() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setMaxSessionTurns(12);
        properties.getMemory().setSessionRecentTurns(6);
        properties.getMemory().setSessionTokenBudget(300);
        properties.getMemory().setSessionSummaryTokenBudget(150);
        TokenBudget tokenBudget = new TokenBudget();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(properties, tokenBudget);
        SessionSnapshot snapshot = new SessionSnapshot("session-50", List.of(), Instant.now(), Instant.now());

        for (int index = 0; index < 50; index++) {
            String question = index == 0
                    ? "记住 payment-service owner=team-payments"
                    : "第 " + index + " 轮检查";
            snapshot = compactor.append(snapshot, new SessionTurn(
                    "exec-" + index,
                    question,
                    index == 49 ? "最终结论：检查 queue lag" : "检查完成 " + index,
                    "SUCCESS",
                    Instant.now()));
        }

        String context = compactor.buildContext(snapshot);
        assertTrue(snapshot.turns().size() <= 6);
        assertEquals(44, snapshot.compactedTurnCount());
        assertTrue(snapshot.summary().contains("owner=team-payments"));
        assertTrue(context.contains("owner=team-payments"));
        assertTrue(context.contains("最终结论"));
        assertTrue(tokenBudget.estimateTokens(context) <= 300);
    }
}
