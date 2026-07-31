package com.kubeoncall.alarm.correlation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class CiCdChangeEventMapper {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("generic", "github", "gitlab", "jenkins", "argocd");

    private final ObjectMapper objectMapper;

    public CiCdChangeEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChangeEvent map(String provider, String eventType, String deliveryId, Map<String, Object> payload) {
        String normalizedProvider = normalizeProvider(provider);
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        String sourceId = first(
                text(safePayload, "changeId"),
                text(safePayload, "id"),
                nested(safePayload, "deployment", "id"),
                nested(safePayload, "workflow_run", "id"),
                nested(safePayload, "object_attributes", "id"),
                nested(safePayload, "metadata", "uid"),
                nested(safePayload, "build", "number"));
        String sourceEvent = first(eventType, text(safePayload, "eventType"), text(safePayload, "event"), "change");
        String state = first(
                text(safePayload, "status"),
                text(safePayload, "action"),
                nested(safePayload, "workflow_run", "status"),
                nested(safePayload, "workflow_run", "conclusion"),
                nested(safePayload, "object_attributes", "status"),
                "unknown");
        String changeId = normalizedProvider + ":"
                + first(deliveryId, String.join(":", first(sourceId, fingerprint(safePayload)), sourceEvent, state));
        String resourceName = first(
                text(safePayload, "resourceName"),
                nested(safePayload, "repository", "full_name"),
                nested(safePayload, "project", "path_with_namespace"),
                nested(safePayload, "application", "metadata", "name"),
                nested(safePayload, "workflow_run", "name"),
                nested(safePayload, "job", "name"),
                text(safePayload, "job_name"),
                "unknown");
        return new ChangeEvent(
                changeId,
                changeType(sourceEvent, safePayload),
                first(
                        text(safePayload, "changedBy"),
                        nested(safePayload, "sender", "login"),
                        text(safePayload, "user_username"),
                        nested(safePayload, "actor", "name"),
                        nested(safePayload, "user", "name"),
                        "unknown"),
                changedAt(safePayload),
                first(text(safePayload, "resourceType"), inferredResourceType(sourceEvent)),
                resourceName,
                first(
                        text(safePayload, "namespace"),
                        nested(safePayload, "application", "spec", "destination", "namespace"),
                        nested(safePayload, "deployment", "environment")),
                first(
                        text(safePayload, "cluster"),
                        nested(safePayload, "application", "spec", "destination", "server")),
                safeDiff(safePayload),
                normalizedProvider,
                first(text(safePayload, "correlationId"), nested(safePayload, "workflow_run", "head_sha"), changeId));
    }

    private String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported change-event provider: " + provider);
        }
        return normalized;
    }

    private String changeType(String eventType, Map<String, Object> payload) {
        String explicit = text(payload, "changeType");
        if (explicit != null) {
            return explicit;
        }
        String normalized = eventType.toLowerCase(Locale.ROOT);
        if (normalized.contains("deployment") || normalized.contains("sync")) {
            return "deployment_image_change";
        }
        if (normalized.contains("config")) {
            return "configmap_change";
        }
        if (normalized.contains("secret")) {
            return "secret_change";
        }
        return "ci_cd_change";
    }

    private String inferredResourceType(String eventType) {
        String normalized = eventType.toLowerCase(Locale.ROOT);
        return normalized.contains("deployment") || normalized.contains("sync") ? "Deployment" : "Pipeline";
    }

    private Instant changedAt(Map<String, Object> payload) {
        String raw = first(
                text(payload, "changedAt"),
                text(payload, "created_at"),
                nested(payload, "workflow_run", "updated_at"),
                nested(payload, "object_attributes", "finished_at"),
                nested(payload, "build", "timestamp"));
        if (raw == null) {
            return Instant.now();
        }
        try {
            if (raw.chars().allMatch(Character::isDigit)) {
                long epoch = Long.parseLong(raw);
                return epoch > 10_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            }
            return Instant.parse(raw);
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return Instant.now();
        }
    }

    private Map<String, Object> safeDiff(Map<String, Object> payload) {
        Map<String, Object> diff = new LinkedHashMap<>();
        copy(diff, "status", first(text(payload, "status"), nested(payload, "workflow_run", "conclusion")));
        copy(diff, "action", text(payload, "action"));
        copy(diff, "ref", first(text(payload, "ref"), text(payload, "checkout_sha")));
        copy(diff, "sha", first(text(payload, "sha"), nested(payload, "workflow_run", "head_sha")));
        copy(diff, "environment", first(text(payload, "environment"), nested(payload, "deployment", "environment")));
        copy(diff, "url", first(text(payload, "url"), nested(payload, "workflow_run", "html_url")));
        return Map.copyOf(diff);
    }

    private void copy(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String fingerprint(Map<String, Object> payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to identify change event", ex);
        }
    }

    private String nested(Map<String, Object> payload, String... path) {
        Object current = payload;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current == null ? null : String.valueOf(current).trim();
    }

    private String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String first(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
