package com.kubeoncall.alarm.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Service
public class AlarmRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AlarmRecoveryService.class);

    private final AlarmRecoveryStore recoveryStore;
    private final ActiveAlarmStore activeAlarmStore;
    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;
    private final AlarmRecoveryFinalizer recoveryFinalizer;
    private final AlarmRecoveryHealthChecker healthChecker;
    private final AlarmRecoveryWindowResolver windowResolver;
    private final AlarmRecoveryAuditRecorder auditRecorder;

    public AlarmRecoveryService(
            AlarmRecoveryStore recoveryStore,
            ActiveAlarmStore activeAlarmStore,
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            AlarmRecoveryFinalizer recoveryFinalizer,
            AlarmRecoveryHealthChecker healthChecker,
            AlarmRecoveryWindowResolver windowResolver,
            AlarmRecoveryAuditRecorder auditRecorder) {
        this.recoveryStore = recoveryStore;
        this.activeAlarmStore = activeAlarmStore;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metricsService = metricsService;
        this.recoveryFinalizer = recoveryFinalizer;
        this.healthChecker = healthChecker;
        this.windowResolver = windowResolver;
        this.auditRecorder = auditRecorder;
    }

    public AlarmRecoveryState begin(NormalizedAlarmEvent event, AlarmPolicy policy, ActiveAlarmState activeState) {
        Instant now = Instant.now();
        AlarmSeverity severity =
                activeState != null && activeState.severity() != null ? activeState.severity() : event.severity();
        Duration stableWindow = windowResolver.recoveryWindow(policy == null ? null : policy.recover(), severity);
        boolean manual = severity == AlarmSeverity.P0 || severity == AlarmSeverity.P1;
        AlarmRecoveryState state = new AlarmRecoveryState(
                activeState == null ? event.fingerprint() : activeState.fingerprint(),
                event.alarmId(),
                severity,
                policy == null ? activeState == null ? null : activeState.policyId() : policy.id(),
                policy == null ? null : policy.recover(),
                now,
                now.plus(stableWindow),
                manual,
                AlarmRecoveryState.PENDING,
                null,
                false,
                null,
                null);
        recoveryStore.savePending(state, windowResolver.stateTtl(stableWindow));
        redisTemplate.delete("alarm-dedup:" + state.fingerprint());
        metricsService.recordAlarmRecovery("pending", severityName(severity));
        return state;
    }

    public Optional<AlarmRecoveryState> cancelIfPending(String fingerprint) {
        Optional<AlarmRecoveryState> existing = recoveryStore.find(fingerprint);
        if (existing.isEmpty() || !existing.get().isPending()) {
            return Optional.empty();
        }
        AlarmRecoveryState cancelled = existing.get().cancelled("same fingerprint fired again", Instant.now());
        recoveryStore.saveFinal(cancelled, windowResolver.finalRetention());
        redisTemplate.delete("alarm-ack:" + fingerprint);
        redisTemplate.delete("alarm-escalation:" + fingerprint);
        metricsService.recordAlarmRecovery("cancelled", severityName(cancelled.severity()));
        return Optional.of(cancelled);
    }

    public AlarmRecoveryState confirmManual(
            String fingerprint, String confirmedBy, boolean healthCheckPassed, String note) {
        AlarmRecoveryState state = recoveryStore
                .find(fingerprint)
                .orElseThrow(() ->
                        new IllegalArgumentException("No recovery candidate found for fingerprint " + fingerprint));
        if (!state.isPending()) {
            throw new IllegalStateException("Recovery candidate is already " + state.status());
        }
        if (!state.manualConfirmationRequired()) {
            throw new IllegalStateException("Recovery candidate does not require manual confirmation");
        }
        if (confirmedBy == null || confirmedBy.isBlank()) {
            throw new IllegalArgumentException("confirmedBy must not be blank");
        }
        if (!healthCheckPassed) {
            throw new IllegalArgumentException("healthCheckPassed must be true");
        }
        Instant now = Instant.now();
        if (now.isBefore(state.confirmAfter())) {
            throw new IllegalStateException("Recovery stability window has not elapsed");
        }
        if (confirmationTimedOut(state, now)) {
            timeout(state, now);
            throw new IllegalStateException("Recovery confirmation window has timed out");
        }
        ensureStillResolved(state);
        AlarmRecoveryHealthChecker.HealthCheckResult healthResult = runHealthCheck(state);
        if (!healthResult.passed()) {
            metricsService.recordAlarmRecovery("health_check_failed", severityName(state.severity()));
            throw new IllegalStateException("Recovery health check failed: " + healthResult.status());
        }
        return confirm(state, confirmedBy.trim(), true, note, now, healthResult);
    }

    public void confirmDueRecoveries() {
        Instant now = Instant.now();
        for (String fingerprint :
                recoveryStore.dueFingerprints(now, properties.getAlarm().getRecoveryBatchSize())) {
            Optional<AlarmRecoveryState> found = recoveryStore.find(fingerprint);
            if (found.isEmpty() || !found.get().isPending()) {
                continue;
            }
            AlarmRecoveryState state = found.get();
            if (confirmationTimedOut(state, now)) {
                timeout(state, now);
                continue;
            }
            if (state.manualConfirmationRequired()) {
                continue;
            }
            try {
                ensureStillResolved(state);
                AlarmRecoveryHealthChecker.HealthCheckResult healthResult = runHealthCheck(state);
                if (!healthResult.passed()) {
                    metricsService.recordAlarmRecovery("health_check_failed", severityName(state.severity()));
                    continue;
                }
                confirm(state, "system", true, "automatic recovery after stable window", now, healthResult);
            } catch (IllegalStateException ex) {
                AlarmRecoveryState cancelled = state.cancelled(ex.getMessage(), now);
                recoveryStore.saveFinal(cancelled, windowResolver.finalRetention());
                metricsService.recordAlarmRecovery("cancelled", severityName(state.severity()));
            }
        }
    }

    public Optional<AlarmRecoveryState> find(String fingerprint) {
        return recoveryStore.find(fingerprint);
    }

    private AlarmRecoveryState confirm(
            AlarmRecoveryState state,
            String actor,
            boolean healthCheckPassed,
            String note,
            Instant now,
            AlarmRecoveryHealthChecker.HealthCheckResult healthResult) {
        AlarmRecoveryState confirmed = state.confirmed(actor, healthCheckPassed, note, now);
        recoveryStore.saveFinal(confirmed, windowResolver.finalRetention());
        redisTemplate.delete("alarm-ack:" + state.fingerprint());
        redisTemplate.delete("alarm-escalation:" + state.fingerprint());
        metricsService.recordAlarmRecovery("confirmed", severityName(state.severity()));
        AlarmRecoveryFinalizer.RecoveryActions recoveryActions = recoveryFinalizer.finalizeRecovery(state, actor, note);
        if (!recoveryActions.success()) {
            metricsService.recordAlarmRecovery("finalization_failed", severityName(state.severity()));
        }
        auditRecorder.recordConfirmation(state, actor, healthCheckPassed, now, healthResult, recoveryActions);
        return confirmed;
    }

    private AlarmRecoveryHealthChecker.HealthCheckResult runHealthCheck(AlarmRecoveryState state) {
        try {
            return healthChecker.check(state);
        } catch (RuntimeException ex) {
            log.warn(
                    "Alarm recovery health check failed: errorType={}",
                    ex.getClass().getSimpleName());
            return new AlarmRecoveryHealthChecker.HealthCheckResult(
                    false,
                    "checker-error",
                    Map.of("errorType", "HealthCheckFailed", "errorMessage", "Health check failed"));
        }
    }

    private boolean confirmationTimedOut(AlarmRecoveryState state, Instant now) {
        long timeoutSeconds = Math.max(60, properties.getAlarm().getRecoveryConfirmationTimeoutSeconds());
        return !now.isBefore(state.confirmAfter().plusSeconds(timeoutSeconds));
    }

    private AlarmRecoveryState timeout(AlarmRecoveryState state, Instant now) {
        AlarmRecoveryState timedOut = state.timedOut("recovery confirmation timeout", now);
        recoveryStore.saveFinal(timedOut, windowResolver.finalRetention());
        metricsService.recordAlarmRecovery("confirmation_timeout", severityName(state.severity()));
        auditRecorder.recordTimeout(state, now);
        return timedOut;
    }

    private void ensureStillResolved(AlarmRecoveryState state) {
        ActiveAlarmState active = activeAlarmStore
                .find(state.fingerprint())
                .orElseThrow(() -> new IllegalStateException("Active alarm state is missing"));
        if (active.status() != AlarmStatus.RESOLVED) {
            throw new IllegalStateException("Alarm fired again before recovery confirmation");
        }
        if (active.lastSeen() != null && active.lastSeen().isAfter(state.candidateAt())) {
            throw new IllegalStateException("Alarm state changed after recovery candidate was created");
        }
    }

    private String severityName(AlarmSeverity severity) {
        return severity == null ? "UNKNOWN" : severity.name();
    }
}
