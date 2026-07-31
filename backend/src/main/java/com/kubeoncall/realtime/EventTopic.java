package com.kubeoncall.realtime;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.kubeoncall.identity.PermissionCode;

/** Public realtime topics and the permission required to subscribe to each topic. */
public enum EventTopic {
    ALARM("alarms", Set.of(PermissionCode.ALARM_READ)),
    APPROVAL("approvals", Set.of(PermissionCode.APPROVAL_READ)),
    EXECUTION("executions", Set.of(PermissionCode.EXECUTION_READ)),
    SANDBOX("sandbox-runs", Set.of(PermissionCode.SANDBOX_READ)),
    TASK(
            "tasks",
            Set.of(
                    PermissionCode.EXECUTION_READ,
                    PermissionCode.KNOWLEDGE_READ,
                    PermissionCode.MEMORY_READ,
                    PermissionCode.SKILL_READ));

    private final String wireName;
    private final Set<String> permissions;

    EventTopic(String wireName, Set<String> permissions) {
        this.wireName = wireName;
        this.permissions = permissions;
    }

    public String wireName() {
        return wireName;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public boolean isAuthorized(Set<String> grantedPermissions) {
        return grantedPermissions != null && permissions.stream().anyMatch(grantedPermissions::contains);
    }

    public static Optional<EventTopic> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(topic -> topic.wireName.equals(normalized))
                .findFirst();
    }

    public static EventTopic fromAggregate(String aggregateType, String eventType) {
        if (aggregateType != null) {
            String normalized = aggregateType.trim().toLowerCase(Locale.ROOT);
            for (EventTopic topic : values()) {
                if (topic.wireName.equals(normalized)
                        || singular(topic.wireName).equals(normalized)) {
                    return topic;
                }
            }
        }
        int separator = eventType == null ? -1 : eventType.indexOf('.');
        String prefix = separator < 0 ? eventType : eventType.substring(0, separator);
        return parse(prefix == null ? null : prefix + "s")
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported realtime aggregate/event type: " + aggregateType + "/" + eventType));
    }

    private static String singular(String value) {
        return value.substring(0, value.length() - 1);
    }
}
