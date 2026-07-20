package com.kubeoncall.alarm.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

class HttpAlarmRecoveryHealthCheckerTest {

    @Test
    void shouldFailClosedWhenRequiredEndpointIsMissing() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setRecoveryHealthCheckRequired(true);
        HttpAlarmRecoveryHealthChecker checker =
                new HttpAlarmRecoveryHealthChecker(mock(ToolHttpClient.class), properties);

        AlarmRecoveryHealthChecker.HealthCheckResult result = checker.check(state());

        assertFalse(result.passed());
        assertTrue(result.status().contains("not-configured"));
    }

    @Test
    void shouldRequireExplicitHealthyResponse() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setRecoveryHealthCheckEndpoint("http://health/recovery");
        ToolHttpClient client = mock(ToolHttpClient.class);
        when(client.post(eq("http://health/recovery"), any(), eq(3000), any()))
                .thenReturn(Map.of("status", "success", "response", Map.of("healthy", true)));
        HttpAlarmRecoveryHealthChecker checker = new HttpAlarmRecoveryHealthChecker(client, properties);

        assertTrue(checker.check(state()).passed());

        when(client.post(eq("http://health/recovery"), any(), eq(3000), any()))
                .thenReturn(Map.of("status", "success", "response", Map.of("status", "ok")));
        assertFalse(checker.check(state()).passed());
    }

    private AlarmRecoveryState state() {
        Instant now = Instant.now();
        return new AlarmRecoveryState(
                "fp-1",
                "alarm-1",
                AlarmSeverity.P1,
                "policy-1",
                "healthy for 10m",
                now.minusSeconds(600),
                now.minusSeconds(60),
                true,
                AlarmRecoveryState.PENDING,
                null,
                false,
                null,
                null);
    }
}
