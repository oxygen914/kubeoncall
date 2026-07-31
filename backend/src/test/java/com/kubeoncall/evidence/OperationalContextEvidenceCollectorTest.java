package com.kubeoncall.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.correlation.MysqlChangeEventRepository;
import com.kubeoncall.alarm.readmodel.AlarmIncidentRecord;
import com.kubeoncall.alarm.readmodel.AlarmReadRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.skill.SkillExecutionPolicy;

class OperationalContextEvidenceCollectorTest {

    @Test
    void shouldCollectAlarmAndChangeFactsFromTheExecutionWindow() {
        Instant now = Instant.now();
        AlarmReadRepository alarms = mock(AlarmReadRepository.class);
        when(alarms.isAvailable()).thenReturn(true);
        when(alarms.findBetween(any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(alarm(now)));
        MysqlChangeEventRepository changes = mock(MysqlChangeEventRepository.class);
        when(changes.isAvailable()).thenReturn(true);
        when(changes.findBetween(any(), any(), anyString(), anyString()))
                .thenReturn(List.of(change("chg-1", now.minusSeconds(60)), change("chg-2", now.minusSeconds(30))));
        KubeOnCallProperties properties = new KubeOnCallProperties();
        OperationalContextEvidenceCollector collector = new OperationalContextEvidenceCollector(
                provider(alarms), provider(changes), new EvidenceItemFactory(new ObjectMapper(), properties));
        EvidenceCollectionScope scope = new EvidenceCollectionScope(
                "exe-1",
                "prod",
                "production",
                "payments",
                new EvidenceResource("Pod", "payment-api", "pod-uid"),
                now.minusSeconds(300),
                now);

        List<EvidenceItem> items = collector.collect(scope, SkillExecutionPolicy.ToolAccess.unrestricted());

        assertEquals(3, items.size());
        assertTrue(items.stream().allMatch(EvidenceItem::succeeded));
        assertTrue(items.stream()
                .anyMatch(item -> item.type() == EvidenceType.ALERT
                        && "alm-1".equals(item.locator().get("alarmId"))));
        assertTrue(items.stream()
                .anyMatch(item -> item.type() == EvidenceType.CHANGE_EVENT
                        && "chg-1".equals(item.locator().get("changeId"))));
        assertEquals(
                2,
                items.stream()
                        .filter(item -> item.type() == EvidenceType.CHANGE_EVENT)
                        .map(EvidenceItem::evidenceId)
                        .distinct()
                        .count());
    }

    @Test
    void shouldExposeUnavailableReadModelsInsteadOfFabricatingContext() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmReadRepository> alarmProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MysqlChangeEventRepository> changeProvider = mock(ObjectProvider.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        OperationalContextEvidenceCollector collector = new OperationalContextEvidenceCollector(
                alarmProvider, changeProvider, new EvidenceItemFactory(new ObjectMapper(), properties));
        Instant now = Instant.now();
        EvidenceCollectionScope scope = new EvidenceCollectionScope(
                "exe-1",
                "prod",
                "production",
                "payments",
                new EvidenceResource("Pod", "payment-api", "pod-uid"),
                now.minusSeconds(300),
                now);

        List<EvidenceItem> items = collector.collect(scope, SkillExecutionPolicy.ToolAccess.unrestricted());

        assertEquals(2, items.size());
        assertTrue(items.stream().allMatch(item -> item.collectionStatus() == EvidenceCollectionStatus.UNAVAILABLE));
    }

    private static AlarmIncidentRecord alarm(Instant now) {
        return new AlarmIncidentRecord(
                1,
                "alm-1",
                "fp-1",
                1,
                "PodOOMKilled",
                "P1",
                1,
                "FIRING",
                "Pod",
                "payment-api",
                "prod",
                "payments",
                "payment-api",
                "container_memory_working_set_bytes",
                100.0,
                90.0,
                "percent",
                Map.of(),
                Map.of(),
                now.minusSeconds(120),
                now.minusSeconds(30),
                null,
                1,
                false,
                null,
                null,
                "policy-1",
                null,
                null,
                0);
    }

    private static ChangeEvent change(String changeId, Instant changedAt) {
        return new ChangeEvent(
                changeId,
                "DEPLOY",
                "release-bot",
                changedAt,
                "Pod",
                "payment-api",
                "payments",
                "prod",
                Map.of("image", "v2"),
                "argocd",
                "corr-1");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
