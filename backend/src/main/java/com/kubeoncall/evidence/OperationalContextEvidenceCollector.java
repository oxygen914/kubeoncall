package com.kubeoncall.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.correlation.MysqlChangeEventRepository;
import com.kubeoncall.alarm.readmodel.AlarmIncidentRecord;
import com.kubeoncall.alarm.readmodel.AlarmReadRepository;
import com.kubeoncall.skill.SkillExecutionPolicy;

/** Collects bounded alarm and change facts from the same MySQL time window as an AI execution. */
@Component
public class OperationalContextEvidenceCollector {

    private static final int MAX_ITEMS_PER_TYPE = 20;

    private final ObjectProvider<AlarmReadRepository> alarmRepositoryProvider;
    private final ObjectProvider<MysqlChangeEventRepository> changeRepositoryProvider;
    private final EvidenceItemFactory factory;

    public OperationalContextEvidenceCollector(
            ObjectProvider<AlarmReadRepository> alarmRepositoryProvider,
            ObjectProvider<MysqlChangeEventRepository> changeRepositoryProvider,
            EvidenceItemFactory factory) {
        this.alarmRepositoryProvider = alarmRepositoryProvider;
        this.changeRepositoryProvider = changeRepositoryProvider;
        this.factory = factory;
    }

    public List<EvidenceItem> collect(EvidenceCollectionScope scope, SkillExecutionPolicy.ToolAccess toolAccess) {
        SkillExecutionPolicy.ToolAccess access =
                toolAccess == null ? SkillExecutionPolicy.ToolAccess.unrestricted() : toolAccess;
        List<EvidenceItem> items = new ArrayList<>();
        items.addAll(
                access.allows("alerts.getActiveAlerts")
                        ? alarms(scope)
                        : List.of(forbidden(scope, EvidenceType.ALERT, "alarm-read-model")));
        items.addAll(
                access.allows("changes.getRecentChanges")
                        ? changes(scope)
                        : List.of(forbidden(scope, EvidenceType.CHANGE_EVENT, "change-event-read-model")));
        return List.copyOf(items);
    }

    private List<EvidenceItem> alarms(EvidenceCollectionScope scope) {
        AlarmReadRepository repository = alarmRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return List.of(
                    factory.unavailable(scope, EvidenceType.ALERT, "alarm-read-model", "ALARM_READ_MODEL_UNAVAILABLE"));
        }
        List<AlarmIncidentRecord> alarms = repository.findBetween(
                scope.start(),
                scope.end(),
                scope.cluster(),
                scope.namespace(),
                scope.resource().name(),
                MAX_ITEMS_PER_TYPE);
        if (alarms.isEmpty()) {
            return List.of(empty(scope, EvidenceType.ALERT, "alarm-read-model"));
        }
        return alarms.stream()
                .map(alarm -> factory.fromMap(scope, EvidenceType.ALERT, alarmValue(alarm), "alarm-read-model"))
                .toList();
    }

    private List<EvidenceItem> changes(EvidenceCollectionScope scope) {
        MysqlChangeEventRepository repository = changeRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return List.of(factory.unavailable(
                    scope, EvidenceType.CHANGE_EVENT, "change-event-read-model", "CHANGE_READ_MODEL_UNAVAILABLE"));
        }
        List<ChangeEvent> changes =
                repository.findBetween(scope.start(), scope.end(), scope.cluster(), scope.namespace()).stream()
                        .filter(change -> scope.resource().name().isBlank()
                                || scope.resource().name().equals(change.resourceName()))
                        .limit(MAX_ITEMS_PER_TYPE)
                        .toList();
        if (changes.isEmpty()) {
            return List.of(empty(scope, EvidenceType.CHANGE_EVENT, "change-event-read-model"));
        }
        return changes.stream()
                .map(change -> factory.fromMap(
                        scope, EvidenceType.CHANGE_EVENT, changeValue(change), "change-event-read-model"))
                .toList();
    }

    private static Map<String, Object> alarmValue(AlarmIncidentRecord alarm) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", "alarm-read-model");
        value.put("collectionStatus", "SUCCEEDED");
        value.put("observedAt", alarm.lastSeen());
        value.put("summary", alarm.severity() + " " + alarm.alertName() + " status=" + alarm.status());
        value.put(
                "snippet",
                "alarmId=%s; fingerprint=%s; lastSeen=%s; metricName=%s"
                        .formatted(alarm.publicId(), alarm.fingerprint(), alarm.lastSeen(), safe(alarm.metricName())));
        value.put("alarmId", alarm.publicId());
        value.put("fingerprint", alarm.fingerprint());
        value.put("severity", alarm.severity());
        value.put("status", alarm.status());
        value.put("metricName", alarm.metricName());
        value.put(
                "resource",
                Map.of(
                        "kind", safe(alarm.resourceType()),
                        "name", safe(alarm.resourceName()),
                        "uid", ""));
        return value;
    }

    private static Map<String, Object> changeValue(ChangeEvent change) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", "change-event-read-model");
        value.put("collectionStatus", "SUCCEEDED");
        value.put("observedAt", change.changedAt());
        value.put("summary", change.changeType() + " on " + safe(change.resourceName()));
        value.put(
                "snippet",
                "changeId=%s; changedAt=%s; source=%s; changedBy=%s; diff=%s"
                        .formatted(
                                change.changeId(),
                                change.changedAt(),
                                safe(change.changeSource()),
                                safe(change.changedBy()),
                                change.diff()));
        value.put("changeId", change.changeId());
        value.put("changeType", change.changeType());
        value.put("changedBy", change.changedBy());
        value.put("changeSource", change.changeSource());
        value.put("diff", change.diff());
        value.put(
                "resource",
                Map.of(
                        "kind", safe(change.resourceType()),
                        "name", safe(change.resourceName()),
                        "uid", ""));
        return value;
    }

    private EvidenceItem empty(EvidenceCollectionScope scope, EvidenceType type, String source) {
        return factory.fromMap(scope, type, Map.of("source", source, "collectionStatus", "EMPTY"), source);
    }

    private EvidenceItem forbidden(EvidenceCollectionScope scope, EvidenceType type, String source) {
        return factory.fromMap(
                scope,
                type,
                Map.of("source", source, "collectionStatus", "FORBIDDEN", "errorType", "SKILL_TOOL_NOT_ALLOWED"),
                source);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
