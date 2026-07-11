package com.kubeoncall.alarm.recovery;

import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AlarmRecoveryService {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)\\bfor\\s+(\\d+)\\s*([smhd])\\b");

    private final AlarmRecoveryStore recoveryStore;
    private final ActiveAlarmStore activeAlarmStore;
    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;
    private final ExecutionAuditService executionAuditService;
    private final KubeOnCallMetricsService metricsService;
    private final AlarmRecoveryFinalizer recoveryFinalizer;
    private final AlarmRecoveryHealthChecker healthChecker;

    public AlarmRecoveryService(AlarmRecoveryStore recoveryStore,
                                ActiveAlarmStore activeAlarmStore,
                                StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                ExecutionAuditService executionAuditService,
                                KubeOnCallMetricsService metricsService) {
        this(recoveryStore, activeAlarmStore, redisTemplate, properties, executionAuditService, metricsService, null);
    }

    public AlarmRecoveryService(AlarmRecoveryStore recoveryStore,
                                ActiveAlarmStore activeAlarmStore,
                                StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                ExecutionAuditService executionAuditService,
                                KubeOnCallMetricsService metricsService,
                                AlarmRecoveryFinalizer recoveryFinalizer) {
        this(recoveryStore, activeAlarmStore, redisTemplate, properties, executionAuditService, metricsService,
                recoveryFinalizer, null);
    }

    @Autowired
    public AlarmRecoveryService(AlarmRecoveryStore recoveryStore,
                                ActiveAlarmStore activeAlarmStore,
                                StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                ExecutionAuditService executionAuditService,
                                KubeOnCallMetricsService metricsService,
                                AlarmRecoveryFinalizer recoveryFinalizer,
                                AlarmRecoveryHealthChecker healthChecker) {
        this.recoveryStore = recoveryStore;
        this.activeAlarmStore = activeAlarmStore;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.executionAuditService = executionAuditService;
        this.metricsService = metricsService;
        this.recoveryFinalizer = recoveryFinalizer;
        this.healthChecker = healthChecker;
    }

    public AlarmRecoveryState begin(NormalizedAlarmEvent event,
                                    AlarmPolicy policy,
                                    ActiveAlarmState activeState) {
        Instant now = Instant.now();
        AlarmSeverity severity = activeState != null && activeState.severity() != null
                ? activeState.severity()
                : event.severity();
        Duration stableWindow = recoveryWindow(policy == null ? null : policy.recover(), severity);
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
                null
        );
        recoveryStore.savePending(state, stateTtl(stableWindow));
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
        recoveryStore.saveFinal(cancelled, finalRetention());
        redisTemplate.delete("alarm-ack:" + fingerprint);
        redisTemplate.delete("alarm-escalation:" + fingerprint);
        metricsService.recordAlarmRecovery("cancelled", severityName(cancelled.severity()));
        return Optional.of(cancelled);
    }

    public AlarmRecoveryState confirmManual(String fingerprint,
                                            String confirmedBy,
                                            boolean healthCheckPassed,
                                            String note) {
        AlarmRecoveryState state = recoveryStore.find(fingerprint)
                .orElseThrow(() -> new IllegalArgumentException("No recovery candidate found for fingerprint " + fingerprint));
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
        for (String fingerprint : recoveryStore.dueFingerprints(now, properties.getAlarm().getRecoveryBatchSize())) {
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
                recoveryStore.saveFinal(cancelled, finalRetention());
                metricsService.recordAlarmRecovery("cancelled", severityName(state.severity()));
            }
        }
    }

    public Optional<AlarmRecoveryState> find(String fingerprint) {
        return recoveryStore.find(fingerprint);
    }

    private AlarmRecoveryState confirm(AlarmRecoveryState state,
                                       String actor,
                                       boolean healthCheckPassed,
                                       String note,
                                       Instant now,
                                       AlarmRecoveryHealthChecker.HealthCheckResult healthResult) {
        AlarmRecoveryState confirmed = state.confirmed(actor, healthCheckPassed, note, now);
        recoveryStore.saveFinal(confirmed, finalRetention());
        redisTemplate.delete("alarm-ack:" + state.fingerprint());
        redisTemplate.delete("alarm-escalation:" + state.fingerprint());
        metricsService.recordAlarmRecovery("confirmed", severityName(state.severity()));
        AlarmRecoveryFinalizer.RecoveryActions recoveryActions = recoveryFinalizer == null
                ? new AlarmRecoveryFinalizer.RecoveryActions(
                        false, state.severity() == AlarmSeverity.P0 || state.severity() == AlarmSeverity.P1,
                        Map.of("status", "failed", "errorMessage", "recovery finalizer unavailable"),
                        Map.of("status", "failed", "errorMessage", "recovery finalizer unavailable"),
                        Map.of("status", "failed", "errorMessage", "recovery finalizer unavailable"))
                : recoveryFinalizer.finalizeRecovery(state, actor, note);
        if (!recoveryActions.success()) {
            metricsService.recordAlarmRecovery("finalization_failed", severityName(state.severity()));
        }
        String auditStatus = recoveryActions.success() ? "RECOVERED" : "RECOVERED_DEGRADED";
        String failureReason = recoveryActions.success() ? null : "Recovery confirmed but external finalization failed";
        LinkedHashMap<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("fingerprint", state.fingerprint());
        auditMetadata.put("severity", severityName(state.severity()));
        auditMetadata.put("policyId", state.policyId() == null ? "" : state.policyId());
        auditMetadata.put("candidateAt", state.candidateAt().toString());
        auditMetadata.put("confirmAfter", state.confirmAfter().toString());
        auditMetadata.put("confirmedAt", now.toString());
        auditMetadata.put("confirmedBy", actor);
        auditMetadata.put("healthCheckPassed", healthCheckPassed);
        auditMetadata.put("healthCheckStatus", healthResult.status());
        auditMetadata.put("healthCheckDetails", healthResult.details());
        auditMetadata.put("manualConfirmation", state.manualConfirmationRequired());
        auditMetadata.put("recoveryFinalizationSucceeded", recoveryActions.success());
        auditMetadata.put("postmortemRequired", recoveryActions.postmortemRequired());
        auditMetadata.put("notificationResult", recoveryActions.notificationResult());
        auditMetadata.put("incidentResolutionResult", recoveryActions.incidentResolutionResult());
        auditMetadata.put("postmortemResult", recoveryActions.postmortemResult());
        executionAuditService.recordAlarmExecution(
                "alarm-recovery-" + state.fingerprint() + "-" + now.toEpochMilli(),
                auditStatus,
                true,
                state.manualConfirmationRequired(),
                "Alarm recovery confirmed by " + actor,
                failureReason,
                List.of("alarm.recovery.confirm", "alertmanager.sendAlertEvent", "incident.resolveIncident",
                        recoveryActions.postmortemRequired() ? "incident.createPostmortem" : "incident.postmortem.not_required"),
                state.candidateAt(),
                auditMetadata
        );
        return confirmed;
    }

    private AlarmRecoveryHealthChecker.HealthCheckResult runHealthCheck(AlarmRecoveryState state) {
        if (healthChecker == null) {
            return AlarmRecoveryHealthChecker.HealthCheckResult.legacyPass();
        }
        try {
            return healthChecker.check(state);
        } catch (RuntimeException ex) {
            return new AlarmRecoveryHealthChecker.HealthCheckResult(
                    false,
                    "checker-error",
                    Map.of("errorType", ex.getClass().getSimpleName(),
                            "errorMessage", ex.getMessage() == null ? "" : ex.getMessage())
            );
        }
    }

    private boolean confirmationTimedOut(AlarmRecoveryState state, Instant now) {
        long timeoutSeconds = Math.max(60, properties.getAlarm().getRecoveryConfirmationTimeoutSeconds());
        return !now.isBefore(state.confirmAfter().plusSeconds(timeoutSeconds));
    }

    private AlarmRecoveryState timeout(AlarmRecoveryState state, Instant now) {
        AlarmRecoveryState timedOut = state.timedOut("recovery confirmation timeout", now);
        recoveryStore.saveFinal(timedOut, finalRetention());
        metricsService.recordAlarmRecovery("confirmation_timeout", severityName(state.severity()));
        executionAuditService.recordAlarmExecution(
                "alarm-recovery-timeout-" + state.fingerprint() + "-" + now.toEpochMilli(),
                "RECOVERY_CONFIRMATION_TIMEOUT",
                false,
                state.manualConfirmationRequired(),
                "Recovery candidate timed out before confirmation",
                "RECOVERY_CONFIRMATION_TIMEOUT",
                List.of("alarm.recovery.timeout"),
                state.candidateAt(),
                Map.of(
                        "fingerprint", state.fingerprint(),
                        "severity", severityName(state.severity()),
                        "policyId", state.policyId() == null ? "" : state.policyId(),
                        "confirmAfter", state.confirmAfter().toString(),
                        "timedOutAt", now.toString(),
                        "manualConfirmation", state.manualConfirmationRequired()
                )
        );
        return timedOut;
    }

    private void ensureStillResolved(AlarmRecoveryState state) {
        ActiveAlarmState active = activeAlarmStore.find(state.fingerprint())
                .orElseThrow(() -> new IllegalStateException("Active alarm state is missing"));
        if (active.status() != AlarmStatus.RESOLVED) {
            throw new IllegalStateException("Alarm fired again before recovery confirmation");
        }
        if (active.lastSeen() != null && active.lastSeen().isAfter(state.candidateAt())) {
            throw new IllegalStateException("Alarm state changed after recovery candidate was created");
        }
    }

    Duration recoveryWindow(String expression, AlarmSeverity severity) {
        if (expression != null) {
            Matcher matcher = DURATION_PATTERN.matcher(expression);
            if (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                return switch (matcher.group(2).toLowerCase()) {
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    case "d" -> Duration.ofDays(amount);
                    default -> fallbackWindow(severity);
                };
            }
        }
        return fallbackWindow(severity);
    }

    private Duration fallbackWindow(AlarmSeverity severity) {
        long seconds = switch (severity == null ? AlarmSeverity.P3 : severity) {
            case P0 -> properties.getAlarm().getP0RecoveryWindowSeconds();
            case P1 -> properties.getAlarm().getP1RecoveryWindowSeconds();
            case P2 -> properties.getAlarm().getP2RecoveryWindowSeconds();
            case P3 -> properties.getAlarm().getP3RecoveryWindowSeconds();
            case INFO -> properties.getAlarm().getInfoRecoveryWindowSeconds();
        };
        return Duration.ofSeconds(Math.max(0, seconds));
    }

    private Duration stateTtl(Duration stableWindow) {
        long seconds = Math.max(properties.getAlarm().getRecoveryStateTtlSeconds(), stableWindow.toSeconds() + 3600);
        return Duration.ofSeconds(seconds);
    }

    private Duration finalRetention() {
        return Duration.ofSeconds(Math.max(3600, properties.getAlarm().getResolvedRetentionSeconds()));
    }

    private String severityName(AlarmSeverity severity) {
        return severity == null ? "UNKNOWN" : severity.name();
    }
}
