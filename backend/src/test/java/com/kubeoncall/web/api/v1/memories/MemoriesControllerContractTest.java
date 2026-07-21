package com.kubeoncall.web.api.v1.memories;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.memory.MemoryGovernanceService;
import com.kubeoncall.memory.MemoryGovernanceService.StartResult;
import com.kubeoncall.memory.mysql.MemoryEntryRecord;
import com.kubeoncall.memory.mysql.MemoryEntryRepository;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRecord;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

class MemoriesControllerContractTest {

    private static final Instant NOW = Instant.parse("2026-07-21T04:00:00Z");

    private MockMvc mockMvc;
    private MemoryEntryRepository memoryRepository;
    private MemoryExtractionTaskRepository extractionRepository;
    private MemoryGovernanceService governanceService;
    private V1Security security;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(MemoryEntryRepository.class);
        extractionRepository = mock(MemoryExtractionTaskRepository.class);
        governanceService = mock(MemoryGovernanceService.class);
        security = mock(V1Security.class);
        when(memoryRepository.isAvailable()).thenReturn(true);
        when(extractionRepository.isAvailable()).thenReturn(true);
        when(governanceService.isAvailable()).thenReturn(true);
        when(security.requirePermission(any())).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoriesController(
                        provider(memoryRepository),
                        provider(extractionRepository),
                        provider(governanceService),
                        security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void listUsesApiFieldNamesAndDoesNotExposeEvidencePayload() throws Exception {
        when(memoryRepository.list(any())).thenReturn(new MemoryEntryRepository.MemoryPage(List.of(memory()), 1));

        mockMvc.perform(get("/api/v1/memories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("mem_123"))
                .andExpect(jsonPath("$.data[0].sourceExecutionId").value("wfe_123"))
                .andExpect(jsonPath("$.data[0].qualityScore").value(0.9))
                .andExpect(jsonPath("$.data[0].evidence").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(1));
        verify(security).requirePermission(PermissionCode.MEMORY_READ);
    }

    @Test
    void extractionListMapsTaskPublicIdToTaskId() throws Exception {
        when(extractionRepository.list(any()))
                .thenReturn(new MemoryExtractionTaskRepository.ExtractionPage(List.of(extraction()), 1));

        mockMvc.perform(get("/api/v1/memories/extractions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("mext_123"))
                .andExpect(jsonPath("$.data[0].taskId").value("tsk_123"))
                .andExpect(jsonPath("$.data[0].sourcePublicId").value("wfe_123"));
    }

    @Test
    void extractionRequiresEvidenceAndReturnsDurableTaskReference() throws Exception {
        when(governanceService.startExtraction(any())).thenReturn(new StartResult(extraction(), task()));

        mockMvc.perform(post("/api/v1/memories/extractions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "EXECUTION",
                                  "sourcePublicId": "wfe_123",
                                  "dedupeKey": "wfe_123:v1"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/memories/extractions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "EXECUTION",
                                  "sourcePublicId": "wfe_123",
                                  "dedupeKey": "wfe_123:v1",
                                  "memoryType": "INCIDENT_SUMMARY",
                                  "scope": "GLOBAL",
                                  "subject": "Node recovery",
                                  "content": "restart kubelet"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.extractionId").value("mext_123"))
                .andExpect(jsonPath("$.data.taskId").value("tsk_123"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(security).requirePermission(PermissionCode.MEMORY_WRITE);
    }

    private static MemoryEntryRecord memory() {
        return new MemoryEntryRecord(
                "mem_123",
                "INCIDENT_SUMMARY",
                "ACTIVE",
                null,
                "wfe_123",
                null,
                Map.of("content", "restart kubelet"),
                new BigDecimal("0.90000"),
                "0".repeat(64),
                null,
                "memory-extraction-mext_123-0",
                NOW,
                null,
                1,
                NOW,
                NOW,
                null,
                null);
    }

    private static MemoryExtractionTaskRecord extraction() {
        return new MemoryExtractionTaskRecord(
                "mext_123",
                "tsk_123",
                "EXECUTION",
                "wfe_123",
                "v1",
                "PENDING",
                "qwen-plus",
                "v1",
                0,
                0,
                Map.of(),
                null,
                null,
                null,
                null,
                1,
                NOW,
                NOW);
    }

    private static AsyncTaskRecord task() {
        return new AsyncTaskRecord(
                1,
                "tsk_123",
                "MEMORY_EXTRACTION",
                "memory_extraction",
                "mext_123",
                "EXECUTION:wfe_123:v1",
                "PENDING",
                "queued",
                0,
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                0,
                0,
                5,
                NOW,
                null,
                null,
                "req_123",
                "trace_123",
                1,
                NOW,
                NOW);
    }

    private static V1Principal principal() {
        UserAccount user = new UserAccount(
                1,
                "usr_1",
                "operator",
                "Operator",
                null,
                null,
                null,
                1,
                "ACTIVE",
                1,
                null,
                null,
                null,
                0,
                java.util.Set.of("OPERATOR"),
                java.util.Set.of(PermissionCode.MEMORY_READ, PermissionCode.MEMORY_WRITE));
        return new V1Principal(user, user.permissions(), V1Principal.AuthMethod.SESSION);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
