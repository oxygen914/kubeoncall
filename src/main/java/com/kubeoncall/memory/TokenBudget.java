package com.kubeoncall.memory;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class TokenBudget {

    private static final String COMPRESSION_MARKER = "\n...[compressed]...\n";

    private final KubeOnCallProperties properties;
    private final ToolHttpClient toolHttpClient;

    public TokenBudget() {
        this(null, null);
    }

    @Autowired
    public TokenBudget(KubeOnCallProperties properties, ToolHttpClient toolHttpClient) {
        this.properties = properties;
        this.toolHttpClient = toolHttpClient;
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        OptionalInt exact = exactTokenCount(text);
        return exact.isPresent() ? exact.getAsInt() : estimateHeuristic(text);
    }

    public String countingMode() {
        return tokenizerConfigured() ? "exact_http_with_heuristic_fallback" : "heuristic";
    }

    private int estimateHeuristic(String text) {
        int asciiUnits = 0;
        int directTokens = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (codePoint <= 0x7f) {
                asciiUnits++;
            } else {
                directTokens++;
            }
        }
        return directTokens + (asciiUnits + 3) / 4;
    }

    private OptionalInt exactTokenCount(String text) {
        if (!tokenizerConfigured()) {
            return OptionalInt.empty();
        }
        Map<String, String> headers = properties.getMemory().getTokenizerApiKey() == null
                        || properties.getMemory().getTokenizerApiKey().isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + properties.getMemory().getTokenizerApiKey());
        Map<String, Object> response = toolHttpClient.post(
                properties.getMemory().getTokenizerEndpoint(),
                Map.of("model", properties.getMemory().getTokenizerModel(), "text", text),
                properties.getMemory().getTokenizerTimeoutMillis(),
                headers,
                Map.of("targetSystem", "tokenizer", "tool", "memory.tokenCount"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return OptionalInt.empty();
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> map)) {
            return OptionalInt.empty();
        }
        Object count = map.get("count");
        if (count instanceof Number number && number.intValue() >= 0) {
            return OptionalInt.of(number.intValue());
        }
        Object tokens = map.get("tokens");
        return tokens instanceof List<?> list ? OptionalInt.of(list.size()) : OptionalInt.empty();
    }

    private boolean tokenizerConfigured() {
        return properties != null
                && toolHttpClient != null
                && properties.getMemory().isTokenizerEnabled()
                && properties.getMemory().getTokenizerEndpoint() != null
                && !properties.getMemory().getTokenizerEndpoint().isBlank();
    }

    public String compactText(String text, int maxTokens) {
        String normalized = text == null ? "" : text.trim();
        int budget = Math.max(1, maxTokens);
        if (estimateTokens(normalized) <= budget) {
            return normalized;
        }
        int markerTokens = estimateTokens(COMPRESSION_MARKER);
        if (budget <= markerTokens + 2) {
            return enforceConfiguredBudget(takeFromStart(normalized, budget), budget);
        }
        int contentBudget = Math.max(2, budget - markerTokens);
        int headBudget = Math.max(1, contentBudget * 3 / 5);
        int tailBudget = Math.max(1, contentBudget - headBudget);
        String compacted =
                takeFromStart(normalized, headBudget) + COMPRESSION_MARKER + takeFromEnd(normalized, tailBudget);
        return enforceConfiguredBudget(compacted, budget);
    }

    private String enforceConfiguredBudget(String text, int budget) {
        if (!tokenizerConfigured() || estimateTokens(text) <= budget) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            int end = safeBoundary(text, middle);
            if (estimateTokens(text.substring(0, end)) <= budget) {
                low = end;
            } else {
                high = Math.max(0, end - 1);
            }
        }
        return text.substring(0, safeBoundary(text, low)).trim();
    }

    private int safeBoundary(String text, int index) {
        int bounded = Math.max(0, Math.min(text.length(), index));
        return bounded > 0
                        && bounded < text.length()
                        && Character.isLowSurrogate(text.charAt(bounded))
                        && Character.isHighSurrogate(text.charAt(bounded - 1))
                ? bounded - 1
                : bounded;
    }

    private String takeFromStart(String text, int maxTokens) {
        int end = 0;
        int asciiUnits = 0;
        int directTokens = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int nextAsciiUnits = asciiUnits + (isAsciiUnit(codePoint) ? 1 : 0);
            int nextDirectTokens = directTokens + (isDirectToken(codePoint) ? 1 : 0);
            if (estimated(nextAsciiUnits, nextDirectTokens) > maxTokens) {
                break;
            }
            asciiUnits = nextAsciiUnits;
            directTokens = nextDirectTokens;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end).trim();
    }

    private String takeFromEnd(String text, int maxTokens) {
        int start = text.length();
        int asciiUnits = 0;
        int directTokens = 0;
        while (start > 0) {
            int previous = text.offsetByCodePoints(start, -1);
            int codePoint = text.codePointAt(previous);
            int nextAsciiUnits = asciiUnits + (isAsciiUnit(codePoint) ? 1 : 0);
            int nextDirectTokens = directTokens + (isDirectToken(codePoint) ? 1 : 0);
            if (estimated(nextAsciiUnits, nextDirectTokens) > maxTokens) {
                break;
            }
            asciiUnits = nextAsciiUnits;
            directTokens = nextDirectTokens;
            start = previous;
        }
        return text.substring(start).trim();
    }

    private boolean isAsciiUnit(int codePoint) {
        return codePoint <= 0x7f && !Character.isWhitespace(codePoint);
    }

    private boolean isDirectToken(int codePoint) {
        return codePoint > 0x7f && !Character.isWhitespace(codePoint);
    }

    private int estimated(int asciiUnits, int directTokens) {
        return directTokens + (asciiUnits + 3) / 4;
    }
}
