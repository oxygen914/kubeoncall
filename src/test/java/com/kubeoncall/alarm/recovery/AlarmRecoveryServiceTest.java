package com.kubeoncall.alarm.recovery;

import com.kubeoncall.alarm.domain.AlarmAction;
import com.kubeoncall.alarm.domain.AlarmCondition;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmRecoveryServiceTest {

    @Test
    void shouldCreateManualRecoveryCandidateFromPolicyWindow() {
        Fixture fixture = new Fixture();

        AlarmRecoveryState state = fixture.service.begin(event(), policy("cpu < 65 for 10m"), active(AlarmSeverity.P1, AlarmStatus.RESOLVED));

        assertTrue(state.manualConfirmationRequired());
        assertEquals(Duration.ofMinutes(10), Duration.between(state.candidateAt(), state.confirmAfter()));
        verify(fixture.store).savePending(eq(state), any(Duration.class));
        verify(fixture.redisTemplate).delete("alarm-dedup:fp-recovery");
        verify(fixture.metrics).recordAlarmRecovery("pending", "P1");
    }

    @Test
    void shouldConfirmManualRecoveryAfterStableWindowAndHealthCheck() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        AlarmRecoveryState pending = pending(AlarmSeverity.P0, true, now.minusSeconds(1200), now.minusSeconds(300));
        when(fixture.store.find("fp-recovery")).thenReturn(Optional.of(pending));
        when(fixture.activeStore.find("fp-recovery")).thenReturn(Optional.of(
                active(AlarmSeverity.P0, AlarmStatus.RESOLVED, pending.candidateAt().minusSeconds(1))));

        AlarmRecoveryState confirmed = fixture.service.confirmManual("fp-recovery", "incident-commander", true, "service healthy");

        assertEquals(AlarmRecoveryState.CONFIRMED, confirmed.status());
        assertEquals("incident-commander", confirmed.confirmedBy());
        assertTrue(confirmed.healthCheckPassed());
        verify(fixture.store).saveFinal(eq(confirmed), any(Duration.class));
        verify(fixture.redisTemplate).delete("alarm-ack:fp-recovery");
        verify(fixture.audit).recordAlarmExecution(
                anyString(), eq("RECOVERED"), anyBoolean(), eq(true), anyString(), any(),
                any(), any(Instant.class), any(Map.class));
    }

    @Test
    void shouldRejectManualRecoveryBeforeStableWindowExpires() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        AlarmRecoveryState pending = pending(AlarmSeverity.P1, true, now, now.plusSeconds(600));
        when(fixture.store.find("fp-recovery")).thenReturn(Optional.of(pending));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> fixture.service.confirmManual("fp-recovery", "oncall", true, "healthy")
        );

        assertTrue(error.getMessage().contains("stability window"));
    }

    @Test
    void shouldAutomaticallyConfirmLowSeverityRecoveryWhenDue() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        AlarmRecoveryState pending = pending(AlarmSeverity.P2, false, now.minusSeconds(600), now.minusSeconds(60));
        when(fixture.store.dueFingerprints(any(Instant.class), eq(100))).thenReturn(Set.of("fp-recovery"));
        when(fixture.store.find("fp-recovery")).thenReturn(Optional.of(pending));
        when(fixture.activeStore.find("fp-recovery")).thenReturn(Optional.of(
                active(AlarmSeverity.P2, AlarmStatus.RESOLVED, pending.candidateAt().minusSeconds(1))));

        fixture.service.confirmDueRecoveries();

        ArgumentCaptor<AlarmRecoveryState> stateCaptor = ArgumentCaptor.forClass(AlarmRecoveryState.class);
        verify(fixture.store).saveFinal(stateCaptor.capture(), any(Duration.class));
        assertEquals(AlarmRecoveryState.CONFIRMED, stateCaptor.getValue().status());
        assertEquals("system", stateCaptor.getValue().confirmedBy());
        verify(fixture.audit).recordAlarmExecution(
                anyString(), eq("RECOVERED"), anyBoolean(), eq(false), anyString(), any(),
                any(), any(Instant.class), any(Map.class));
    }

    @Test
    void shouldKeepRecoveryPendingWhenActiveHealthCheckFails() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        AlarmRecoveryState pending = pending(AlarmSeverity.P2, false, now.minusSeconds(600), now.minusSeconds(60));
        when(fixture.store.dueFingerprints(any(Instant.class), eq(100))).thenReturn(Set.of("fp-recovery"));
        when(fixture.store.find("fp-recovery")).thenReturn(Optional.of(pending));
        when(fixture.activeStore.find("fp-recovery")).thenReturn(Optional.of(
                active(AlarmSeverity.P2, AlarmStatus.RESOLVED, pending.candidateAt().minusSeconds(1))));
        AlarmRecoveryHealthChecker healthChecker = mock(AlarmRecoveryHealthChecker.class);
        when(healthChecker.check(pending)).thenReturn(new AlarmRecoveryHealthChecker.HealthCheckResult(
                false, "unhealthy", Map.of("probe", "failed")));
        AlarmRecoveryService service = new AlarmRecoveryService(
                fixture.store, fixture.activeStore, fixture.redisTemplate, fixture.properties,
                fixture.audit, fixture.metrics, fixture.finalizer, healthChecker);

        service.confirmDueRecoveries();

        verify(fixture.store, never()).saveFinal(any(), any());
        verify(fixture.metrics).recordAlarmRecovery("health_check_failed", "P2");
    }

    @Test
    void shouldAuditManualRecoveryTimeout() {
        Fixture fixture = new Fixture();
        fixture.properties.getAlarm().setRecoveryConfirmationTimeoutSeconds(60);
        Instant now = Instant.now();
        AlarmRecoveryState pending = pending(AlarmSeverity.P1, true, now.minusSeconds(300), now.minusSeconds(120));
        when(fixture.store.dueFingerprints(any(Instant.class), eq(100))).thenReturn(Set.of("fp-recovery"));
        when(fixture.store.find("fp-recovery")).thenReturn(Optional.of(pending));

        fixture.service.confirmDueRecoveries();

        ArgumentCaptor<AlarmRecoveryState> stateCaptor = ArgumentCaptor.forClass(AlarmRecoveryState.class);
        verify(fixture.store).saveFinal(stateCaptor.capture(), any(Duration.class));
        assertEquals(AlarmRecoveryState.TIMED_OUT, stateCaptor.getValue().status());
        verify(fixture.metrics).recordAlarmRecovery("confirmation_timeout", "P1");
        verify(fixture.audit).recordAlarmExecution(
                anyString(), eq("RECOVERY_CONFIRMATION_TIMEOUT"), anyBoolean(), eq(true), anyString(),
                eq("RECOVERY_CONFIRMATION_TIMEOUT"), any(), any(Instant.class), any(Map.class));
    }

    @Test
    void shouldCancelPendingRecoveryAndClearOldAcknowledgementWhenAlarmFiresAgain() {
        Fixture fixture = new Fixture();
        AlarmRecoveryState pending = pending(
                AlarmSeverity.P1, true, Instant.now().minusSeconds(60), Instant.now().plusSeconds(540));
        when(fixture.store.find("fp-recovery")).thenReturn(Optional.of(pending));

        Optional<AlarmRecoveryState> cancelled = fixture.service.cancelIfPending("fp-recovery");

        assertTrue(cancelled.isPresent());
        assertEquals(AlarmRecoveryState.CANCELLED, cancelled.get().status());
        verify(fixture.redisTemplate).delete("alarm-ack:fp-recovery");
        verify(fixture.redisTemplate).delete("alarm-escalation:fp-recovery");
    }

    private static AlarmRecoveryState pending(AlarmSeverity severity,
                                              boolean manual,
                                              Instant candidateAt,
                                              Instant confirmAfter) {
        return new AlarmRecoveryState(
                "fp-recovery", "alarm-recovery", severity, "policy-1", "healthy for 10m",
                candidateAt, confirmAfter, manual, AlarmRecoveryState.PENDING,
                null, false, null, null
        );
    }

    private static AlarmPolicy policy(String recover) {
        return new AlarmPolicy(
                "policy-1", "HostHighCpuUsageP1", "host", "host.cpu.usage_percent",
                AlarmResourceType.NODE, AlarmSeverity.P1,
                new AlarmCondition("HostHighCpuUsageP1", "host.cpu.usage_percent", AlarmResourceType.NODE, ">", 70.0, "5m", Map.of()),
                "cpu > 70", "15m", recover, "runbook-host-cpu-high", "infra",
                new AlarmAction("host-resource", "oncall", false, false, List.of()), Map.of()
        );
    }

    private static NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "alarm-recovery", "fp-recovery", "HostHighCpuUsageP1", "prometheus", "resolved", AlarmSeverity.INFO,
                AlarmResourceType.NODE, "node-a", "cluster-a", "prod", "infra",
                "host.cpu.usage_percent", 30.0, 70.0, "%", "10m", Map.of(), Map.of(),
                "runbook-host-cpu-high", AlarmStatus.RESOLVED, Instant.now(), "recovered", Map.of()
        );
    }

    private static ActiveAlarmState active(AlarmSeverity severity, AlarmStatus status) {
        return active(severity, status, Instant.now().minusSeconds(1));
    }

    private static ActiveAlarmState active(AlarmSeverity severity, AlarmStatus status, Instant lastSeen) {
        return new ActiveAlarmState(
                "fp-recovery", "alarm-recovery", "HostHighCpuUsageP1", "cluster-a", "prod", "infra", "node-a",
                severity, status, "policy-1", lastSeen.minusSeconds(600), lastSeen, 2
        );
    }

    private static class Fixture {
        private final AlarmRecoveryStore store = mock(AlarmRecoveryStore.class);
        private final ActiveAlarmStore activeStore = mock(ActiveAlarmStore.class);
        private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        private final KubeOnCallProperties properties = new KubeOnCallProperties();
        private final ExecutionAuditService audit = mock(ExecutionAuditService.class);
        private final KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        private final AlarmRecoveryFinalizer finalizer = mock(AlarmRecoveryFinalizer.class);
        private final AlarmRecoveryService service;

        private Fixture() {
            when(finalizer.finalizeRecovery(any(AlarmRecoveryState.class), anyString(), any()))
                    .thenReturn(new AlarmRecoveryFinalizer.RecoveryActions(
                            true, true, Map.of("status", "success"), Map.of("status", "success"), Map.of("status", "success")));
            service = new AlarmRecoveryService(
                    store, activeStore, redisTemplate, properties, audit, metrics, finalizer);
        }
    }
}
