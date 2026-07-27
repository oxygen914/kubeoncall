package com.kubeoncall.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kubeoncall.observability.SensitiveDataRedactor;
import com.kubeoncall.sandbox.domain.SandboxClassification;

/**
 * Builds the bounded, redacted input package consumed by a Sandbox Job.
 *
 * <p>It deliberately accepts collected evidence rather than performing Kubernetes calls itself:
 * collection remains in the existing read-only tools, while this component establishes the security
 * boundary before content reaches an isolated Job. The generated hash covers canonical content, so
 * the same evidence in a different input order has the same identity.
 */
@Component
public class DiagnosticEvidenceBuilder {

    static final int MAX_ITEMS = 100;
    static final int MAX_LOG_LINES_PER_ITEM = 500;
    static final int MAX_FIELD_LENGTH = 8192;
    static final long MAX_WINDOW_SECONDS = 24 * 60 * 60;

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----.*?-----END (?:[A-Z0-9 ]+ )?PRIVATE KEY-----");
    private static final Pattern SECRET_YAML_DATA =
            Pattern.compile("(?im)^(\\s*(?:data|stringData|token|serviceAccountToken)\\s*:\\s*).*$");
    private static final Pattern SECRET_KIND = Pattern.compile("(?im)^\\s*kind\\s*:\\s*Secret\\s*$");

    private final SensitiveDataRedactor redactor;
    private final ObjectMapper canonicalMapper;

    @Autowired
    public DiagnosticEvidenceBuilder(ObjectMapper objectMapper) {
        this(SensitiveDataRedactor.STANDARD, objectMapper);
    }

    DiagnosticEvidenceBuilder(SensitiveDataRedactor redactor, ObjectMapper objectMapper) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.canonicalMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public EvidencePackage build(BuildRequest request) {
        if (request == null || blank(request.requestId()) || request.collectedAt() == null) {
            throw new IllegalArgumentException("requestId and collectedAt are required");
        }
        validateWindow(request.windowStart(), request.windowEnd());
        List<EvidenceItem> source = request.items() == null ? List.of() : request.items();
        if (source.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("evidence item count exceeds " + MAX_ITEMS);
        }
        List<PackagedItem> items = new ArrayList<>(source.size());
        for (EvidenceItem item : source) {
            items.add(packageItem(item));
        }
        items.sort(Comparator.comparing(PackagedItem::type).thenComparing(PackagedItem::source));
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", "v1");
        canonical.put("requestId", request.requestId());
        canonical.put(
                "windowStart",
                request.windowStart() == null ? null : request.windowStart().toString());
        canonical.put(
                "windowEnd",
                request.windowEnd() == null ? null : request.windowEnd().toString());
        canonical.put("collectedAt", request.collectedAt().toString());
        canonical.put("classification", SandboxClassification.INTERNAL.name());
        canonical.put("items", items);
        byte[] bytes = json(canonical);
        return new EvidencePackage(bytes, sha256(bytes), SandboxClassification.INTERNAL, List.copyOf(items));
    }

    private PackagedItem packageItem(EvidenceItem item) {
        if (item == null || item.type() == null || blank(item.source())) {
            throw new IllegalArgumentException("evidence type and source are required");
        }
        String value = item.content() == null ? "" : item.content();
        if (isBinary(value)) {
            value = "[BINARY_OMITTED sha256=" + sha256(value.getBytes(StandardCharsets.ISO_8859_1)) + "]";
        } else {
            value = trimLogs(value, item.type());
            value = PRIVATE_KEY.matcher(value).replaceAll("[REDACTED_PRIVATE_KEY]");
            if (item.type() == EvidenceType.RESOURCE_YAML
                    && SECRET_KIND.matcher(value).find()) {
                value = SECRET_YAML_DATA.matcher(value).replaceAll("$1[REDACTED]");
            }
            value = redactor.redactText(value);
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            value = value.substring(0, MAX_FIELD_LENGTH) + "…[TRUNCATED]";
        }
        return new PackagedItem(
                item.type().name(), item.source().trim(), value, sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String trimLogs(String value, EvidenceType type) {
        if (type != EvidenceType.LOG) {
            return value;
        }
        String[] lines = value.split("\\R", MAX_LOG_LINES_PER_ITEM + 2);
        if (lines.length <= MAX_LOG_LINES_PER_ITEM) {
            return value;
        }
        return String.join("\n", java.util.Arrays.copyOf(lines, MAX_LOG_LINES_PER_ITEM)) + "\n…[TRUNCATED_LINES]";
    }

    private static boolean isBinary(String value) {
        if (value.indexOf('\u0000') >= 0) {
            return true;
        }
        int controls = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x09 || (current > 0x0d && current < 0x20)) {
                controls++;
            }
        }
        return !value.isEmpty() && controls * 20 > value.length();
    }

    private static void validateWindow(Instant start, Instant end) {
        if (start == null && end == null) {
            return;
        }
        if (start == null
                || end == null
                || end.isBefore(start)
                || end.minusSeconds(MAX_WINDOW_SECONDS).isAfter(start)) {
            throw new IllegalArgumentException("evidence window must be ordered and no longer than 24 hours");
        }
    }

    private byte[] json(Object value) {
        try {
            return canonicalMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize diagnostic evidence", ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum EvidenceType {
        LOG,
        EVENT,
        RESOURCE_DESCRIPTION,
        METRIC_WINDOW,
        RESOURCE_YAML
    }

    public record EvidenceItem(EvidenceType type, String source, String content) {}

    public record BuildRequest(
            String requestId, Instant windowStart, Instant windowEnd, Instant collectedAt, List<EvidenceItem> items) {}

    public record PackagedItem(String type, String source, String content, String sha256) {}

    public record EvidencePackage(
            byte[] content, String sha256, SandboxClassification classification, List<PackagedItem> items) {
        public String base64() {
            return Base64.getEncoder().encodeToString(content);
        }
    }
}
