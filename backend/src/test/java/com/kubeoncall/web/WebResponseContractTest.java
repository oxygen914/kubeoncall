package com.kubeoncall.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillIndexEntry;
import com.kubeoncall.skill.SkillRegistry;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.VerifierCapability;

class WebResponseContractTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.index())
                .thenReturn(List.of(new SkillIndexEntry(
                        "payment-oom-triage",
                        "Payment OOM triage",
                        "Investigate payment OOM events",
                        List.of("OOMKilled"),
                        List.of("payment"),
                        "MEDIUM",
                        "v1",
                        "BUILTIN",
                        "classpath:skills/payment-oom-triage/SKILL.md",
                        true)));
        when(registry.loadErrors()).thenReturn(List.of());

        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ToolDefinition plannerTool = new ToolDefinition(
                "prometheus.query",
                "mcp",
                "Query Prometheus",
                true,
                false,
                List.of(TaskType.QUERY_METRICS),
                List.of("query"),
                List.of("prometheus"));
        when(catalog.plannerTools()).thenReturn(List.of(plannerTool));
        when(catalog.executorTools()).thenReturn(List.of());
        when(catalog.verifierCapabilities())
                .thenReturn(List.of(new VerifierCapability("verifierThinkNode", "policy", "Checks task policy")));

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new StatusController(),
                        new SkillController(
                                registry, mock(KubeOnCallMetricsService.class), mock(ExecutionAuditService.class)),
                        new ToolController(catalog))
                .build();
    }

    @Test
    void shouldKeepStatusAndSkillListJsonShapesStable() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("KubeOnCall"))
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].id").value("payment-oom-triage"))
                .andExpect(jsonPath("$.skills[0].maxRisk").value("MEDIUM"))
                .andExpect(jsonPath("$.skills[0].enabled").value(true))
                .andExpect(jsonPath("$.loadErrors").isArray());
    }

    @Test
    void shouldKeepToolCatalogJsonShapeStable() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planner[0].name").value("prometheus.query"))
                .andExpect(jsonPath("$.planner[0].supportedTaskTypes[0]").value("QUERY_METRICS"))
                .andExpect(jsonPath("$.executor").isArray())
                .andExpect(jsonPath("$.verifier[0].node").value("verifierThinkNode"))
                .andExpect(jsonPath("$.verifier[0].type").value("policy"));
    }
}
