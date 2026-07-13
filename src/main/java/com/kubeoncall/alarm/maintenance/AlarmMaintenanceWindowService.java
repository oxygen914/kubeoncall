package com.kubeoncall.alarm.maintenance;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;

@Service
public class AlarmMaintenanceWindowService {

    private static final Logger log = LoggerFactory.getLogger(AlarmMaintenanceWindowService.class);

    private final AlarmMaintenanceWindowStore store;

    public AlarmMaintenanceWindowService(AlarmMaintenanceWindowStore store) {
        this.store = store;
    }

    public AlarmMaintenanceWindow create(
            Instant startsAt,
            Instant endsAt,
            Map<String, String> matchers,
            String reason,
            String createdBy,
            String approvedBy,
            String approvalReference) {
        Instant now = Instant.now();
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        if (!endsAt.isAfter(now)) {
            throw new IllegalArgumentException("endsAt must be in the future");
        }
        if (matchers == null || matchers.isEmpty()) {
            throw new IllegalArgumentException("at least one matcher is required");
        }
        requireText(reason, "reason");
        requireText(createdBy, "createdBy");
        requireText(approvedBy, "approvedBy");
        requireText(approvalReference, "approvalReference");
        if (createdBy.trim().equalsIgnoreCase(approvedBy.trim())) {
            throw new IllegalArgumentException("createdBy and approvedBy must be different");
        }
        Map<String, String> normalizedMatchers = matchers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().trim(),
                        entry -> entry.getValue().trim()));
        if (normalizedMatchers.isEmpty()) {
            throw new IllegalArgumentException("at least one non-blank matcher is required");
        }
        AlarmMaintenanceWindow window = new AlarmMaintenanceWindow(
                UUID.randomUUID().toString(),
                startsAt,
                endsAt,
                normalizedMatchers,
                reason.trim(),
                createdBy.trim(),
                approvedBy.trim(),
                approvalReference.trim(),
                now);
        store.save(window);
        return window;
    }

    public Optional<AlarmMaintenanceWindow> matchingWindow(NormalizedAlarmEvent event, Instant now) {
        if (event == null || now == null) {
            return Optional.empty();
        }
        try {
            return store.activeAt(now).stream()
                    .filter(window -> matches(window, event))
                    .findFirst();
        } catch (RuntimeException ex) {
            log.warn("Maintenance window lookup failed; alarm processing continues: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean revoke(String id) {
        return store.delete(id);
    }

    boolean matches(AlarmMaintenanceWindow window, NormalizedAlarmEvent event) {
        return window.matchers().entrySet().stream()
                .allMatch(entry -> wildcardMatches(actualValue(entry.getKey(), event), entry.getValue()));
    }

    private String actualValue(String key, NormalizedAlarmEvent event) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "cluster" -> event.cluster();
            case "namespace" -> event.namespace();
            case "service" -> event.service();
            case "resourcetype" ->
                event.resourceType() == null ? null : event.resourceType().name();
            case "resourcename" -> event.resourceName();
            case "alertname" -> event.alertName();
            default ->
                key.regionMatches(true, 0, "label.", 0, "label.".length())
                        ? event.labels().get(key.substring("label.".length()))
                        : null;
        };
    }

    private boolean wildcardMatches(String actual, String expectedPattern) {
        if (actual == null || expectedPattern == null) {
            return false;
        }
        String regex = Pattern.quote(expectedPattern).replace("*", "\\E.*\\Q");
        return actual.matches("(?i)^" + regex + "$");
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
