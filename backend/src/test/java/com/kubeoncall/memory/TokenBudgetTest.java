package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

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

    @Test
    void shouldUseConfiguredTokenizerAndFallBackToHeuristicWhenUnavailable() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setTokenizerEnabled(true);
        properties.getMemory().setTokenizerEndpoint("http://tokenizer/count");
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(any(), any(), any(Integer.class), any(), any()))
                .thenReturn(Map.of("status", "success", "response", Map.of("tokens", List.of(1, 2, 3, 4, 5))));
        TokenBudget budget = new TokenBudget(properties, httpClient);

        assertEquals(5, budget.estimateTokens("a tokenizer-backed sentence"));
        assertEquals("count_endpoint_with_heuristic_fallback", budget.countingMode());

        when(httpClient.post(any(), any(), any(Integer.class), any(), any())).thenReturn(Map.of("status", "failed"));
        assertTrue(budget.estimateTokens("中文测试") >= 4);
    }
}
