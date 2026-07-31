package com.kubeoncall.alarm.recovery;

import java.util.Map;

public interface AlarmRecoveryHealthChecker {

    HealthCheckResult check(AlarmRecoveryState state);

    record HealthCheckResult(boolean passed, String status, Map<String, Object> details) {
        public HealthCheckResult {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static HealthCheckResult legacyPass() {
            return new HealthCheckResult(true, "legacy-checker-unavailable", Map.of("legacyFallback", true));
        }
    }
}
