package com.kubeoncall.web.api.v1.approvals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.approval.mysql.ApprovalRequestRecord;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Security;

class ApprovalsControllerContractTest {

    private MockMvc mockMvc;
    private MySqlApprovalRepository repository;
    private V1Security security;

    @BeforeEach
    void setUp() {
        repository = mock(MySqlApprovalRepository.class);
        security = mock(V1Security.class);
        when(repository.isAvailable()).thenReturn(true);
        mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalsController(provider(repository), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void listReturnsPagedProjectionAndForwardsFilters() throws Exception {
        when(repository.list(any())).thenReturn(new MySqlApprovalRepository.ApprovalPage(List.of(pending()), 1));

        mockMvc.perform(get("/api/v1/approvals")
                        .queryParam("page", "0")
                        .queryParam("size", "0")
                        .queryParam("status", "PENDING,APPROVED")
                        .queryParam("risk", "HIGH")
                        .queryParam("executionId", "exe_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("apr_1"))
                .andExpect(jsonPath("$.data[0].executionId").value("exe_1"))
                .andExpect(jsonPath("$.data[0].summary").value("Restart node"))
                .andExpect(jsonPath("$.data[0].dedupeKey").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        ArgumentCaptor<MySqlApprovalRepository.ApprovalQuery> query =
                ArgumentCaptor.forClass(MySqlApprovalRepository.ApprovalQuery.class);
        verify(repository).list(query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue().statuses()).containsExactly("PENDING", "APPROVED");
        org.assertj.core.api.Assertions.assertThat(query.getValue().riskLevels())
                .containsExactly("HIGH");
        org.assertj.core.api.Assertions.assertThat(query.getValue().executionPublicId())
                .isEqualTo("exe_1");
        verify(security).requirePermission(PermissionCode.APPROVAL_READ);
    }

    @Test
    void detailReturnsContextAndStructuredDecision() throws Exception {
        when(repository.findByPublicId("apr_1")).thenReturn(Optional.of(approved()));

        mockMvc.perform(get("/api/v1/approvals/apr_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("RESTART_NODE"))
                .andExpect(jsonPath("$.data.context.node").value("worker-01"))
                .andExpect(jsonPath("$.data.decision.value").value("APPROVED"))
                .andExpect(jsonPath("$.data.decision.decidedBy").value(8))
                .andExpect(jsonPath("$.data.requestedBy").doesNotExist());
    }

    @Test
    void detailReturnsNotFoundEnvelope() throws Exception {
        when(repository.findByPublicId("apr_missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/approvals/apr_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void unavailableRepositoryReturnsServiceUnavailable() throws Exception {
        when(repository.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void listRequiresApprovalReadPermission() throws Exception {
        when(security.requirePermission(PermissionCode.APPROVAL_READ)).thenThrow(V1ApiException.forbidden("no"));

        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static ApprovalRequestRecord pending() {
        return approval("PENDING", null, null, null, null);
    }

    private static ApprovalRequestRecord approved() {
        return approval("APPROVED", 8L, Instant.parse("2026-07-20T01:02:00Z"), "APPROVED", "safe");
    }

    private static ApprovalRequestRecord approval(
            String status, Long decidedBy, Instant decidedAt, String decision, String comment) {
        return new ApprovalRequestRecord(
                71,
                "apr_1",
                91,
                "exe_1",
                "RESTART_NODE",
                "dedupe-secret",
                "HIGH",
                status,
                Map.of("summary", "Restart node", "node", "worker-01"),
                7,
                Instant.parse("2026-07-20T01:00:00Z"),
                decidedBy,
                decidedAt,
                decision,
                comment,
                Instant.parse("2026-07-20T02:00:00Z"),
                2,
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:02:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
