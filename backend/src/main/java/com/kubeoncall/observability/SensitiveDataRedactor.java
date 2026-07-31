package com.kubeoncall.observability;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded defensive redaction for audit/log copies.
 *
 * <p>The redactor never mutates its input. Business identifiers, statuses and numeric fields are
 * preserved; only sensitive keys and credential-shaped text are masked.
 */
public final class SensitiveDataRedactor {

    public static final String REDACTED = "[REDACTED]";
    public static final SensitiveDataRedactor STANDARD = new SensitiveDataRedactor(8, 100, 8192);

    private static final Set<String> EXACT_SENSITIVE_KEYS = Set.of(
            "authorization",
            "proxyauthorization",
            "cookie",
            "setcookie",
            "token",
            "password",
            "passwd",
            "pwd",
            "secret",
            "clientsecret",
            "apikey",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "sessiontoken",
            "privatekey",
            "credential",
            "credentials");

    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern AUTH_HEADER =
            Pattern.compile("(?i)(\\b(?:Proxy-)?Authorization\\s*[:=]\\s*(?:Bearer|Basic))\\s+[^\\s,;]+");
    private static final Pattern AUTH_ASSIGNMENT = Pattern.compile("(?i)(\\b(?:Proxy-)?Authorization\\s*[:=]\\s*)"
            + "(?!(?:Bearer|Basic)\\s+\\[REDACTED\\])"
            + "(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)\\b(?:Cookie|Set-Cookie)\\s*:\\s*[^\\r\\n]+");
    private static final Pattern JWT =
            Pattern.compile("\\b[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern COMMON_SECRET =
            Pattern.compile("(?i)([\"']?(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|"
                    + "auth[_-]?token|token|client[_-]?secret|cookie)[\"']?\\s*[:=]\\s*)"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)");
    private static final Pattern PROVIDER_KEY =
            Pattern.compile("\\b(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,})\\b");

    private final int maxDepth;
    private final int maxCollectionSize;
    private final int maxTextLength;

    public SensitiveDataRedactor(int maxDepth, int maxCollectionSize, int maxTextLength) {
        if (maxDepth < 1 || maxCollectionSize < 1 || maxTextLength < 32) {
            throw new IllegalArgumentException("Redaction bounds must be positive and text length at least 32");
        }
        this.maxDepth = maxDepth;
        this.maxCollectionSize = maxCollectionSize;
        this.maxTextLength = maxTextLength;
    }

    public Map<String, Object> redactMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Object redacted = redactValue(values, 0);
        if (redacted instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    public Object redact(Object value) {
        return redactValue(value, 0);
    }

    public String redactText(String value) {
        if (value == null) {
            return null;
        }
        String bounded = value.length() <= maxTextLength ? value : value.substring(0, maxTextLength) + "…[TRUNCATED]";
        String redacted = AUTH_HEADER.matcher(bounded).replaceAll("$1 " + REDACTED);
        redacted = AUTH_ASSIGNMENT.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = COOKIE_HEADER.matcher(redacted).replaceAll("Cookie: " + REDACTED);
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
        redacted = JWT.matcher(redacted).replaceAll("[REDACTED_JWT]");
        redacted = replaceSecretAssignments(redacted);
        return PROVIDER_KEY.matcher(redacted).replaceAll(REDACTED);
    }

    private Object redactValue(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof CharSequence sequence) {
            return redactText(sequence.toString());
        }
        if (depth >= maxDepth) {
            return "[MAX_DEPTH]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= maxCollectionSize) {
                    result.put("_truncated", true);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                result.put(key, isSensitiveKey(key) ? REDACTED : redactValue(entry.getValue(), depth + 1));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                if (count++ >= maxCollectionSize) {
                    result.add("[TRUNCATED]");
                    break;
                }
                result.add(redactValue(item, depth + 1));
            }
            return Collections.unmodifiableList(result);
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            int length = Math.min(Array.getLength(value), maxCollectionSize);
            for (int index = 0; index < length; index++) {
                result.add(redactValue(Array.get(value, index), depth + 1));
            }
            if (Array.getLength(value) > length) {
                result.add("[TRUNCATED]");
            }
            return Collections.unmodifiableList(result);
        }
        return redactText(String.valueOf(value));
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (EXACT_SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("password")
                || normalized.endsWith("secret")
                || normalized.endsWith("apikey")
                || normalized.endsWith("token")
                || normalized.endsWith("privatekey");
    }

    private static String replaceSecretAssignments(String value) {
        Matcher matcher = COMMON_SECRET.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + REDACTED));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
