package com.kubeoncall.notification.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical notification content. It intentionally contains no provider credentials, target IDs or
 * raw provider card JSON.
 */
public record NotificationMessage(
        String eventId,
        String eventType,
        String routingKey,
        NotificationPriority priority,
        String title,
        String summary,
        Map<String, String> facts,
        List<NotificationAction> actions,
        Instant occurredAt) {

    private static final int MAX_FACTS = 50;
    private static final int MAX_ACTIONS = 10;

    public NotificationMessage {
        eventId = requireText(eventId, "eventId", 128);
        eventType = requireText(eventType, "eventType", 128);
        routingKey = requireText(routingKey, "routingKey", 128);
        priority = Objects.requireNonNull(priority, "priority must not be null");
        title = requireText(title, "title", 512);
        summary = requireText(summary, "summary", 2000);
        facts = immutableFacts(facts);
        actions = immutableActions(actions);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static Map<String, String> immutableFacts(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > MAX_FACTS) {
            throw new IllegalArgumentException("facts must not contain more than " + MAX_FACTS + " entries");
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> copied.put(requireText(key, "fact key", 128), requireText(value, "fact value", 1000)));
        return Collections.unmodifiableMap(copied);
    }

    private static List<NotificationAction> immutableActions(List<NotificationAction> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (source.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("actions must not contain more than " + MAX_ACTIONS + " entries");
        }
        List<NotificationAction> copied = new ArrayList<>(source.size());
        for (NotificationAction action : source) {
            copied.add(Objects.requireNonNull(action, "notification action must not be null"));
        }
        return List.copyOf(copied);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = requireText(value, field);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
