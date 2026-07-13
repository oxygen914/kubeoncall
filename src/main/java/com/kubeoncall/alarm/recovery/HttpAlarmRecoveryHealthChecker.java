package com.kubeoncall.alarm.recovery;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class HttpAlarmRecoveryHealthChecker implements AlarmRecoveryHealthChecker {

    private final ToolHttpClient httpClient;
    private final KubeOnCallProperties properties;

    public HttpAlarmRecoveryHealthChecker(ToolHttpClient httpClient, KubeOnCallProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public HealthCheckResult check(AlarmRecoveryState state) {
        String endpoint = properties.getAlarm().getRecoveryHealthCheckEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            boolean required = properties.getAlarm().isRecoveryHealthCheckRequired();
            return new HealthCheckResult(
                    !required,
                    required ? "endpoint-not-configured" : "disabled",
                    Map.of("required", required, "endpointConfigured", false));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("fingerprint", state.fingerprint());
        request.put("alarmId", state.alarmId() == null ? "" : state.alarmId());
        request.put("severity", state.severity() == null ? "" : state.severity().name());
        request.put("policyId", state.policyId() == null ? "" : state.policyId());
        request.put("recoverExpression", state.recoverExpression() == null ? "" : state.recoverExpression());
        Map<String, Object> response = httpClient.post(
                endpoint,
                request,
                properties.getAlarm().getRecoveryHealthCheckTimeoutMillis(),
                Map.of("checker", "alarm-recovery", "fingerprint", state.fingerprint()));
        boolean transportSuccess = "success".equalsIgnoreCase(String.valueOf(response.get("status")));
        boolean healthy = transportSuccess && explicitHealthy(response.get("response"));
        LinkedHashMap<String, Object> details = new LinkedHashMap<>(response);
        details.put("transportSuccess", transportSuccess);
        details.put("explicitHealthy", healthy);
        return new HealthCheckResult(healthy, healthy ? "healthy" : "unhealthy", details);
    }

    private boolean explicitHealthy(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> map)) {
            return false;
        }
        Object healthy = map.get("healthy");
        if (healthy instanceof Boolean value) {
            return value;
        }
        return "true".equalsIgnoreCase(String.valueOf(healthy));
    }
}
