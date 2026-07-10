package com.kubeoncall.memory;

import org.springframework.stereotype.Component;

@Component
public class TokenBudget {

    private static final String COMPRESSION_MARKER = "\n...[compressed]...\n";

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
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

    public String compactText(String text, int maxTokens) {
        String normalized = text == null ? "" : text.trim();
        int budget = Math.max(1, maxTokens);
        if (estimateTokens(normalized) <= budget) {
            return normalized;
        }
        int markerTokens = estimateTokens(COMPRESSION_MARKER);
        if (budget <= markerTokens + 2) {
            return takeFromStart(normalized, budget);
        }
        int contentBudget = Math.max(2, budget - markerTokens);
        int headBudget = Math.max(1, contentBudget * 3 / 5);
        int tailBudget = Math.max(1, contentBudget - headBudget);
        return takeFromStart(normalized, headBudget)
                + COMPRESSION_MARKER
                + takeFromEnd(normalized, tailBudget);
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
