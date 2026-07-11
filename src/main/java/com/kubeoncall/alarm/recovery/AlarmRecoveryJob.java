package com.kubeoncall.alarm.recovery;

import com.kubeoncall.service.KubeOnCallMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "kubeoncall.alarm.recovery-scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AlarmRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(AlarmRecoveryJob.class);

    private final AlarmRecoveryService recoveryService;
    private final KubeOnCallMetricsService metricsService;

    public AlarmRecoveryJob(AlarmRecoveryService recoveryService,
                            KubeOnCallMetricsService metricsService) {
        this.recoveryService = recoveryService;
        this.metricsService = metricsService;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.alarm.recovery-check-interval-millis:30000}")
    public void confirmDueRecoveries() {
        try {
            recoveryService.confirmDueRecoveries();
        } catch (RuntimeException ex) {
            metricsService.recordAlarmRecovery("scan_failed", "unknown");
            log.warn("Alarm recovery scan failed: {}", ex.getMessage());
        }
    }
}
