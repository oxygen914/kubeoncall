package com.kubeoncall.alarm.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.readmodel.AlarmIncidentRecord;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;

/** Maps alarm lifecycle facts to the provider-neutral, allow-listed notification contract. */
public final class AlarmNotificationMessageFactory {

    private static final String DEFAULT_ROUTE = "oncall";

    private AlarmNotificationMessageFactory() {}

    public static NotificationMessage firing(
            NormalizedAlarmEvent event, AlarmEvaluationResult evaluation, String phase) {
        String eventType = "alarm.firing." + requireText(phase, "phase");
        Map<String, String> facts = alarmFacts(event, evaluation);
        return new NotificationMessage(
                eventId(eventType, event.fingerprint(), occurredAt(event).toString()),
                eventType,
                route(evaluation),
                priority(severity(event, evaluation)),
                title(event, "告警触发"),
                safe(event.summary(), "告警已触发", 2000),
                facts,
                List.of(),
                occurredAt(event));
    }

    public static NotificationMessage escalation(
            NormalizedAlarmEvent event, AlarmEvaluationResult evaluation, long count, long threshold) {
        Map<String, String> facts = new LinkedHashMap<>(alarmFacts(event, evaluation));
        facts.put("重复次数", String.valueOf(count));
        facts.put("升级阈值", String.valueOf(threshold));
        return new NotificationMessage(
                eventId("alarm.escalated", event.fingerprint(), String.valueOf(count)),
                "alarm.escalated",
                route(evaluation),
                priority(severity(event, evaluation)),
                title(event, "告警升级"),
                "告警长时间未确认，已达到升级阈值",
                facts,
                List.of(),
                occurredAt(event));
    }

    public static NotificationMessage recovery(
            AlarmRecoveryState state, String actor, String note, Instant occurredAt) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "告警 ID", state.alarmId());
        put(facts, "指纹", state.fingerprint());
        put(facts, "严重级别", state.severity() == null ? null : state.severity().name());
        put(facts, "策略", state.policyId());
        put(facts, "确认人", actor);
        put(facts, "备注", note);
        return new NotificationMessage(
                eventId("alarm.recovered", state.fingerprint(), occurredAt.toString()),
                "alarm.recovered",
                DEFAULT_ROUTE,
                priority(state.severity()),
                "告警已恢复",
                "恢复条件已经确认，事件进入已解决状态",
                facts,
                List.of(),
                occurredAt);
    }

    public static NotificationMessage lifecycle(
            AlarmIncidentRecord alarm,
            String eventType,
            String title,
            String summary,
            String actor,
            Instant occurredAt) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "告警 ID", alarm.publicId());
        put(facts, "告警名称", alarm.alertName());
        put(facts, "严重级别", alarm.severity());
        put(facts, "资源", alarm.resourceName());
        put(facts, "集群", alarm.cluster());
        put(facts, "命名空间", alarm.namespace());
        put(facts, "操作人", actor);
        return new NotificationMessage(
                eventId(eventType, alarm.publicId(), occurredAt.toString()),
                eventType,
                DEFAULT_ROUTE,
                priority(alarm.severity()),
                title,
                summary,
                facts,
                List.of(),
                occurredAt);
    }

    private static Map<String, String> alarmFacts(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "告警 ID", event.alarmId());
        put(facts, "指纹", event.fingerprint());
        AlarmSeverity severity = severity(event, evaluation);
        put(facts, "严重级别", severity == null ? event.rawSeverity() : severity.name());
        put(facts, "资源", event.resourceName());
        put(facts, "集群", event.cluster());
        put(facts, "命名空间", event.namespace());
        put(facts, "服务", event.service());
        put(facts, "运行手册", evaluation == null ? event.runbookId() : evaluation.runbookId());
        return facts;
    }

    private static String route(AlarmEvaluationResult evaluation) {
        if (evaluation != null
                && evaluation.matchedPolicy() != null
                && evaluation.matchedPolicy().actions() != null
                && evaluation.matchedPolicy().actions().notificationChannel() != null
                && !evaluation.matchedPolicy().actions().notificationChannel().isBlank()) {
            return evaluation.matchedPolicy().actions().notificationChannel().trim();
        }
        return DEFAULT_ROUTE;
    }

    private static AlarmSeverity severity(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation) {
        return evaluation != null && evaluation.finalSeverity() != null ? evaluation.finalSeverity() : event.severity();
    }

    private static NotificationPriority priority(AlarmSeverity severity) {
        if (severity == null) {
            return NotificationPriority.NORMAL;
        }
        return switch (severity) {
            case P0 -> NotificationPriority.CRITICAL;
            case P1 -> NotificationPriority.HIGH;
            case P2 -> NotificationPriority.NORMAL;
            case P3, INFO -> NotificationPriority.LOW;
        };
    }

    private static NotificationPriority priority(String severity) {
        if (severity == null) {
            return NotificationPriority.NORMAL;
        }
        try {
            return priority(AlarmSeverity.valueOf(severity.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return NotificationPriority.NORMAL;
        }
    }

    private static String title(NormalizedAlarmEvent event, String fallback) {
        return safe(event.alertName(), fallback, 512);
    }

    private static Instant occurredAt(NormalizedAlarmEvent event) {
        return event.occurredAt() == null ? Instant.now() : event.occurredAt();
    }

    private static String eventId(String eventType, String aggregateId, String discriminator) {
        String canonical = eventType + "\u0000" + safe(aggregateId, "unknown") + "\u0000" + discriminator;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "nevt_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void put(Map<String, String> facts, String key, String value) {
        if (value != null && !value.isBlank()) {
            String normalized = value.trim();
            facts.put(key, normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000));
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safe(String value, String fallback, int maxLength) {
        String normalized = safe(value, fallback);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
