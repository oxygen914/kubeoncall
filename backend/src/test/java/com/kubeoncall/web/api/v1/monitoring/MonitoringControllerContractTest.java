package com.kubeoncall.web.api.v1.monitoring;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.monitoring.MonitoringDataSourceException;
import com.kubeoncall.monitoring.MonitoringQueryService;
import com.kubeoncall.monitoring.MonitoringViews.Cluster;
import com.kubeoncall.monitoring.MonitoringViews.ClusterList;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Security;

class MonitoringControllerContractTest {

    private MockMvc mockMvc;
    private MonitoringQueryService service;
    private V1Security security;

    @BeforeEach
    void setUp() {
        service = mock(MonitoringQueryService.class);
        security = mock(V1Security.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitoringController(service, security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void clustersReturnsStableEnvelopeAndRequiresDashboardRead() throws Exception {
        when(service.clusters())
                .thenReturn(new ClusterList(
                        List.of(new Cluster("prod", true, true, 3)), Instant.parse("2026-07-28T10:00:00Z")));

        mockMvc.perform(get("/api/v1/monitoring/clusters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clusters[0].name").value("prod"))
                .andExpect(jsonPath("$.data.clusters[0].nodeCount").value(3));

        verify(security).requirePermission(PermissionCode.DASHBOARD_READ);
    }

    @Test
    void rejectsUnboundedAndInvalidParameters() throws Exception {
        mockMvc.perform(get("/api/v1/monitoring/pods").param("cluster", "prod").param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/monitoring/nodes/worker-1/cpu")
                        .param("cluster", "prod")
                        .param("window", "30d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void prometheusFailureUsesServiceUnavailableEnvelope() throws Exception {
        when(service.clusters()).thenThrow(new MonitoringDataSourceException("connection refused"));

        mockMvc.perform(get("/api/v1/monitoring/clusters"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Monitoring data source is unavailable"));
    }
}
