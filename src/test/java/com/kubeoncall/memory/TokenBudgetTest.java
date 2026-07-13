package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBudgetTest {

    @Test
    void shouldEstimateCjkAndCompactWithinBudget() {
        TokenBudget budget = new TokenBudget();
        String text = "关键事实 payment-service owner=team-payments " + "后续诊断内容".repeat(80);

        String compacted = budget.compactText(text, 40);

        assertTrue(budget.estimateTokens("中文测试") >= 4);
        assertTrue(budget.estimateTokens(compacted) <= 40);
        assertTrue(compacted.contains("关键事实"));
        assertTrue(compacted.contains("compressed"));
    }
}
