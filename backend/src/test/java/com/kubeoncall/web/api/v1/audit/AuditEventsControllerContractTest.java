package com.kubeoncall.web.api.v1.audit;

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

import com.kubeoncall.audit.read.AuditEventRecord;
import com.kubeoncall.audit.read.AuditEventRepository;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.observability.SensitiveDataRedactor;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Security;

class AuditEventsControllerContractTest {

    private MockMvc mockMvc;
    private AuditEventRepository repository;
    private V1Security security;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        security = mock(V1Security.class);
        when(repository.isAvailable()).thenReturn(true);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditEventsController(
                        provider(repository), provider(SensitiveDataRedactor.STANDARD), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void listForwardsFiltersUsesPageEnvelopeAndReturnsRedactedStructuredDiff() throws Exception {
        when(repository.list(any())).thenReturn(new AuditEventRepository.AuditPage(List.of(event()), 1));

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("actor", "usr_1")
                        .param("action", "alarm.acknowledge")
                        .param("resourceType", "alarm")
                        .param("resourceId", "alm_1")
                        .param("result", "SUCCESS")
                        .param("requestId", "req_origin")
                        .param("from", "2026-07-20T00:00:00Z")
                        .param("to", "2026-07-22T00:00:00Z")
                        .param("page", "2")
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("oaud_1"))
                .andExpect(jsonPath("$.data[0].actor.type").value("USER"))
                .andExpect(jsonPath("$.data[0].actor.id").value("usr_1"))
                .andExpect(jsonPath("$.data[0].actor.internalId").doesNotExist())
                .andExpect(jsonPath("$.data[0].resource.type").value("alarm"))
                .andExpect(jsonPath("$.data[0].resource.id").value("alm_1"))
                .andExpect(jsonPath("$.data[0].reason").value("password=[REDACTED]"))
                .andExpect(jsonPath("$.data[0].before.password").value(SensitiveDataRedactor.REDACTED))
                .andExpect(jsonPath("$.data[0].after.authorization").value(SensitiveDataRedactor.REDACTED))
                .andExpect(jsonPath("$.data[0].diff.added.authorization").value(SensitiveDataRedactor.REDACTED))
                .andExpect(jsonPath("$.data[0].diff.changed.password").doesNotExist())
                .andExpect(jsonPath("$.data[0].diff.changed.status.before").value("FIRING"))
                .andExpect(jsonPath("$.data[0].diff.changed.status.after").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data[0].diff.changed['nested.attempt'].before")
                        .value(1))
                .andExpect(jsonPath("$.data[0].diff.changed['nested.attempt'].after")
                        .value(2))
                .andExpect(jsonPath("$.page.number").value(2))
                .andExpect(jsonPath("$.page.size").value(100))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        ArgumentCaptor<AuditEventRepository.AuditQuery> query =
                ArgumentCaptor.forClass(AuditEventRepository.AuditQuery.class);
        verify(repository).list(query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue())
                .extracting(
                        AuditEventRepository.AuditQuery::actor,
                        AuditEventRepository.AuditQuery::action,
                        AuditEventRepository.AuditQuery::resourceType,
                        AuditEventRepository.AuditQuery::resourceId,
                        AuditEventRepository.AuditQuery::result,
                        AuditEventRepository.AuditQuery::requestId,
                        AuditEventRepository.AuditQuery::page,
                        AuditEventRepository.AuditQuery::size)
                .containsExactly("usr_1", "alarm.acknowledge", "alarm", "alm_1", "SUCCESS", "req_origin", 2, 100);
        verify(security).requirePermission(PermissionCode.AUDIT_READ);
    }

    @Test
    void detailReturnsPublicProjectionWithoutDatabaseId() throws Exception {
        when(repository.find("oaud_1")).thenReturn(Optional.of(event()));

        mockMvc.perform(get("/api/v1/audit-events/oaud_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("oaud_1"))
                .andExpect(jsonPath("$.data.internalId").doesNotExist())
                .andExpect(jsonPath("$.data.requestId").value("req_origin"))
                .andExpect(jsonPath("$.data.traceId").value("trc_1"))
                .andExpect(jsonPath("$.data.sourceIp").value("127.0.0.1"))
                .andExpect(jsonPath("$.data.browser").value("Chrome 126"))
                .andExpect(jsonPath("$.data.occurredAt").exists());
    }

    @Test
    void missingAuditEventUsesStableNotFoundEnvelope() throws Exception {
        when(repository.find("oaud_missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/audit-events/oaud_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    void invalidTimeRangeIsRejectedBeforeRepositoryQuery() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("from", "2026-07-22T00:00:00Z")
                        .param("to", "2026-07-21T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void disabledMysqlReadModelReturnsServiceUnavailable() throws Exception {
        when(repository.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/audit-events"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    private static AuditEventRecord event() {
        return new AuditEventRecord(
                "oaud_1",
                "USER",
                "usr_1",
                "Alice",
                "alarm.acknowledge",
                "alarm",
                "alm_1",
                "SUCCESS",
                "password=do-not-return",
                Map.of(
                        "status", "FIRING",
                        "password", "do-not-return",
                        "nested", Map.of("attempt", 1)),
                Map.of(
                        "status", "ACKNOWLEDGED",
                        "password", "different-secret",
                        "authorization", "Bearer super-secret-token",
                        "nested", Map.of("attempt", 2)),
                "req_origin",
                "trc_1",
                "127.0.0.1",
                "Chrome 126",
                Instant.parse("2026-07-21T01:02:03Z"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
