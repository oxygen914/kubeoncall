package com.kubeoncall.alarm.recovery;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.tool.ToolExecutor;

class AlarmRecoveryFinalizerTest {

    @Test
    void shouldNotifyResolveIncidentAndCreatePostmortemForP1() {
        ToolExecutor alertmanager = executor("alertmanager");
        ToolExecutor incident = executor("incident");
        when(alertmanager.execute(eq("sendAlertEvent"), any())).thenReturn(ok());
        when(incident.execute(eq("resolveIncident"), any())).thenReturn(ok());
        when(incident.execute(eq("createPostmortem"), any())).thenReturn(ok());
        AlarmRecoveryFinalizer finalizer = new AlarmRecoveryFinalizer(List.of(alertmanager, incident));

        AlarmRecoveryFinalizer.RecoveryActions result =
                finalizer.finalizeRecovery(state(AlarmSeverity.P1), "oncall", "healthy");

        assertTrue(result.success());
        assertTrue(result.postmortemRequired());
        verify(alertmanager).execute(eq("sendAlertEvent"), any());
        verify(incident).execute(eq("resolveIncident"), any());
        verify(incident).execute(eq("createPostmortem"), any());
    }

    private static ToolExecutor executor(String kind) {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.getExecutorKind()).thenReturn(kind);
        return executor;
    }

    private static Map<String, Object> ok() {
        return Map.of("status", "success", "httpStatus", 200);
    }

    private static AlarmRecoveryState state(AlarmSeverity severity) {
        Instant now = Instant.now();
        return new AlarmRecoveryState(
                "fp-finalize",
                "alarm-finalize",
                severity,
                "policy-1",
                "healthy for 10m",
                now.minusSeconds(900),
                now.minusSeconds(300),
                true,
                AlarmRecoveryState.CONFIRMED,
                "oncall",
                true,
                "healthy",
                now);
    }
}
