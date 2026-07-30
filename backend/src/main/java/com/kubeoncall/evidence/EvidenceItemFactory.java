package com.kubeoncall.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.observability.SensitiveDataRedactor;

/** Converts heterogeneous tool responses into the bounded Evidence v2 contract. */
@Component
public class EvidenceItemFactory {

    private static final Map<String, EvidenceType> PLANNER_TYPES = Map.ofEntries(
            Map.entry("sop", EvidenceType.SOP),
            Map.entry("topology", EvidenceType.RESOURCE_STATE),
            Map.entry("serviceMetadata", EvidenceType.RESOURCE_STATE),
            Map.entry("resourceSnapshot", EvidenceType.RESOURCE_STATE),
            Map.entry("activeAlerts", EvidenceType.ALERT),
            Map.entry("metricsContext", EvidenceType.METRIC),
            Map.entry("changeEvents", EvidenceType.CHANGE_EVENT));

    private final ObjectMapper objectMapper;
    private final int maxSnippetChars;
    private final SensitiveDataRedactor redactor = SensitiveDataRedactor.STANDARD;

    public EvidenceItemFactory(ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.objectMapper = objectMapper;
        this.maxSnippetChars = Math.max(256, properties.getAiOperations().getEvidenceMaxSnippetChars());
    }

    public List<EvidenceItem> fromPlannerPayload(EvidenceCollectionScope scope, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }
        List<EvidenceItem> items = new ArrayList<>();
        PLANNER_TYPES.forEach((key, type) -> {
            Object value = payload.get(key);
            if (value instanceof Map<?, ?> map) {
                items.add(fromMap(scope, type, stringMap(map), key));
            }
        });
        return List.copyOf(items);
    }

    public EvidenceItem fromMap(
            EvidenceCollectionScope scope, EvidenceType type, Map<String, Object> value, String defaultSource) {
        Map<String, Object> safeValue = value == null ? Map.of() : value;
        EvidenceCollectionStatus status = collectionStatus(safeValue);
        String source = text(safeValue, "source", text(safeValue, "tool", defaultSource));
        EvidenceResource resource = resource(scope.resource(), safeValue);
        Instant observedAt = instant(safeValue, "observedAt", instant(safeValue, "timestamp", Instant.now()));
        String rawSnippet = firstText(safeValue, "snippet", "message", "summary", "rawResponse", "response", "value");
        if (type == EvidenceType.RESOURCE_STATE) {
            rawSnippet = resourceStateSnippet(safeValue, rawSnippet);
        } else if (type == EvidenceType.METRIC) {
            rawSnippet = metricSnippet(safeValue, rawSnippet);
        }
        if (rawSnippet.isBlank() && status == EvidenceCollectionStatus.SUCCEEDED) {
            rawSnippet = serialize(safeValue);
        }
        String redactedSnippet = redactor.redactText(rawSnippet);
        boolean truncated = redactedSnippet.length() > maxSnippetChars;
        String snippet = truncate(redactedSnippet, maxSnippetChars);
        String summary = truncate(
                redactor.redactText(firstText(safeValue, "summary", "message", "reason")),
                Math.min(1000, maxSnippetChars));
        if (summary.isBlank()) {
            summary = defaultSummary(type, status, resource, source);
        }
        String contentHash = "sha256:" + sha256(contentIdentity(type, rawSnippet, safeValue));
        String evidenceId = "evd_"
                + sha256(String.join("|", scope.executionId(), type.name(), source, resource.uid(), contentHash))
                        .substring(0, 32);
        Map<String, Object> locator = locator(safeValue);
        long freshnessSeconds = Math.max(
                0, java.time.Duration.between(observedAt, Instant.now()).toSeconds());
        String errorType = text(safeValue, "errorType", "");
        Map<String, Object> metadata = metadata(safeValue);
        return new EvidenceItem(
                evidenceId,
                scope.executionId(),
                type,
                source,
                scope.cluster(),
                scope.namespace(),
                resource,
                observedAt,
                new EvidenceWindow(scope.start(), scope.end()),
                summary,
                snippet,
                locator,
                freshnessSeconds,
                true,
                truncated || booleanValue(safeValue.get("truncated")),
                contentHash,
                status,
                errorType,
                text(safeValue, "artifactReference", ""),
                metadata);
    }

    public EvidenceItem unavailable(EvidenceCollectionScope scope, EvidenceType type, String source, String errorType) {
        return fromMap(
                scope,
                type,
                Map.of(
                        "source",
                        source,
                        "collectionStatus",
                        "UNAVAILABLE",
                        "errorType",
                        errorType == null ? "DEPENDENCY_UNAVAILABLE" : errorType),
                source);
    }

    private EvidenceResource resource(EvidenceResource fallback, Map<String, Object> value) {
        Object candidate = value.get("resource");
        if (candidate instanceof Map<?, ?> map) {
            Map<String, Object> resource = stringMap(map);
            return new EvidenceResource(
                    text(resource, "kind", fallback.kind()),
                    text(resource, "name", fallback.name()),
                    text(resource, "uid", fallback.uid()));
        }
        return new EvidenceResource(
                text(value, "resourceKind", text(value, "kind", fallback.kind())),
                text(value, "resourceName", text(value, "name", fallback.name())),
                text(value, "resourceUid", text(value, "uid", fallback.uid())));
    }

    private Map<String, Object> locator(Map<String, Object> value) {
        Object candidate = value.get("locator");
        if (candidate instanceof Map<?, ?> map) {
            return redactor.redactMap(stringMap(map));
        }
        Map<String, Object> locator = new LinkedHashMap<>();
        put(locator, "query", value.get("query"));
        put(locator, "sequence", value.get("sequence"));
        put(locator, "pod", value.get("pod"));
        put(locator, "container", value.get("container"));
        put(locator, "stream", value.get("stream"));
        put(locator, "alarmId", value.get("alarmId"));
        put(locator, "changeId", value.get("changeId"));
        put(locator, "fingerprint", value.get("fingerprint"));
        return redactor.redactMap(locator);
    }

    private Map<String, Object> metadata(Map<String, Object> value) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "latencyMs", value.get("latencyMs"));
        put(metadata, "httpStatus", value.get("httpStatus"));
        put(metadata, "reason", value.get("reason"));
        put(metadata, "eventType", value.get("eventType"));
        put(metadata, "count", value.get("count"));
        put(metadata, "previous", value.get("previous"));
        put(metadata, "simulation", value.get("simulation"));
        put(metadata, "environmentFilterApplied", value.get("environmentFilterApplied"));
        put(metadata, "severity", value.get("severity"));
        put(metadata, "status", value.get("status"));
        put(metadata, "metricName", value.get("metricName"));
        put(metadata, "memoryTimelineCollectionStatus", value.get("memoryTimelineCollectionStatus"));
        put(metadata, "memoryTimelineErrorType", value.get("memoryTimelineErrorType"));
        put(metadata, "changeType", value.get("changeType"));
        put(metadata, "changeSource", value.get("changeSource"));
        put(metadata, "sopId", firstPresent(value, "sopId", "runbookId", "documentId", "id"));
        put(metadata, "version", firstPresent(value, "version", "runbookVersion", "datasetVersion", "dataset_version"));
        put(metadata, "section", firstPresent(value, "section", "heading"));
        put(metadata, "title", firstPresent(value, "title", "name"));
        return redactor.redactMap(metadata);
    }

    private EvidenceCollectionStatus collectionStatus(Map<String, Object> value) {
        String raw = text(value, "collectionStatus", "");
        if (raw.isBlank()) {
            raw = "success".equalsIgnoreCase(text(value, "status", "")) ? "SUCCEEDED" : "FAILED";
        }
        try {
            return EvidenceCollectionStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return EvidenceCollectionStatus.FAILED;
        }
    }

    private String defaultSummary(
            EvidenceType type, EvidenceCollectionStatus status, EvidenceResource resource, String source) {
        String target = resource.name().isBlank() ? "current scope" : resource.kind() + "/" + resource.name();
        return type + " evidence for " + target + " from " + source + " is " + status;
    }

    private Instant instant(Map<String, Object> value, String key, Instant fallback) {
        Object candidate = value.get(key);
        if (candidate instanceof Instant instant) {
            return instant;
        }
        if (candidate instanceof Number number) {
            long epoch = number.longValue();
            return epoch > 10_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        if (candidate != null) {
            try {
                return Instant.parse(String.valueOf(candidate));
            } catch (DateTimeParseException ignored) {
                // Fall through to the source collection time.
            }
        }
        return fallback;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(redactor.redact(value));
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String resourceStateSnippet(Map<String, Object> value, String summary) {
        Map<String, Object> details = new LinkedHashMap<>();
        put(details, "resource", value.get("resource"));
        put(details, "phase", value.get("phase"));
        put(details, "ready", value.get("ready"));
        put(details, "nodeName", value.get("nodeName"));
        put(details, "nodeContext", value.get("nodeContext"));
        put(details, "containers", value.get("containers"));
        put(details, "conditions", value.get("conditions"));
        put(details, "capacity", value.get("capacity"));
        put(details, "allocatable", value.get("allocatable"));
        put(details, "lease", value.get("lease"));
        put(details, "affectedPodCount", value.get("affectedPodCount"));
        put(details, "affectedPods", value.get("affectedPods"));
        put(details, "affectedPodsTruncated", value.get("affectedPodsTruncated"));
        put(details, "allocatedRequestsWithinScope", value.get("allocatedRequestsWithinScope"));
        put(details, "remainingAllocatableWithinScope", value.get("remainingAllocatableWithinScope"));
        put(details, "impactScope", value.get("impactScope"));
        put(details, "impactCollectionErrors", value.get("impactCollectionErrors"));
        if (details.isEmpty()) {
            return summary;
        }
        String structured = serialize(details);
        return summary == null || summary.isBlank() ? structured : summary + "\n" + structured;
    }

    private String metricSnippet(Map<String, Object> value, String summary) {
        if (!value.containsKey("memoryTimeline")) {
            return summary;
        }
        String structured = serialize(Map.of("memoryTimeline", value.get("memoryTimeline")));
        return summary == null || summary.isBlank() ? structured : summary + "\n" + structured;
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String firstText(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            Object candidate = value.get(key);
            if (candidate != null && !String.valueOf(candidate).isBlank()) {
                return String.valueOf(candidate);
            }
        }
        return "";
    }

    private static String text(Map<String, Object> value, String key, String fallback) {
        Object candidate = value.get(key);
        return candidate == null || String.valueOf(candidate).isBlank() ? fallback : String.valueOf(candidate);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String contentIdentity(EvidenceType type, String rawSnippet, Map<String, Object> value) {
        if (type != EvidenceType.POD_LOG || !value.containsKey("previous")) {
            return rawSnippet;
        }
        return rawSnippet + "\nlogMode=" + (booleanValue(value.get("previous")) ? "previous" : "current");
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            values.put(key, value);
        }
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
