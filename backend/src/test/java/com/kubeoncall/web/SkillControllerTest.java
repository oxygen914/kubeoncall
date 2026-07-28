package com.kubeoncall.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillRegistry;
import com.kubeoncall.web.dto.SkillStateResponse;

class SkillControllerTest {

    @Test
    void shouldAuditReloadAndStateChanges() {
        SkillRegistry registry = mock(SkillRegistry.class);
        KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        when(registry.reload()).thenReturn(new SkillRegistry.ReloadResult(4, 1, List.of()));
        SkillController controller = new SkillController(registry, metrics, audit);

        SkillRegistry.ReloadResult result = controller.reload();
        SkillStateResponse disabled = controller.disable("pod-oom-triage");

        assertEquals(4, result.loaded());
        assertEquals(false, disabled.enabled());
        verify(audit).recordSkillOperation(eq("reload"), eq("success"), any(), any(Instant.class), any(Map.class));
        verify(audit).recordSkillOperation(eq("disable"), eq("success"), any(), any(Instant.class), any(Map.class));
    }
}
