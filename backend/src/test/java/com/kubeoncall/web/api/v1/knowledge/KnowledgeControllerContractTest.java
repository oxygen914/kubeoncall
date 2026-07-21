package com.kubeoncall.web.api.v1.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.knowledge.KnowledgeDocumentCommandService;
import com.kubeoncall.knowledge.KnowledgeDocumentCommandService.MutationOutcome;
import com.kubeoncall.knowledge.KnowledgeImportSubmissionService;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRepository;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentVersionRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

class KnowledgeControllerContractTest {

    private MockMvc mockMvc;
    private KnowledgeDocumentRepository documents;
    private KnowledgeImportRepository imports;
    private KnowledgeDocumentCommandService commands;
    private KnowledgeImportSubmissionService submissions;
    private V1Security security;

    @BeforeEach
    void setUp() {
        documents = mock(KnowledgeDocumentRepository.class);
        imports = mock(KnowledgeImportRepository.class);
        commands = mock(KnowledgeDocumentCommandService.class);
        submissions = mock(KnowledgeImportSubmissionService.class);
        security = mock(V1Security.class);
        when(documents.isAvailable()).thenReturn(true);
        when(imports.isAvailable()).thenReturn(true);
        when(commands.isAvailable()).thenReturn(true);
        when(submissions.isAvailable()).thenReturn(true);
        when(security.requirePermission(PermissionCode.KNOWLEDGE_WRITE)).thenReturn(principal());
        when(security.requirePermission(PermissionCode.KNOWLEDGE_DELETE)).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(
                        provider(documents), provider(imports), provider(commands), provider(submissions), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void documentListUsesPageEnvelopeAndReadPermission() throws Exception {
        when(documents.listDocuments(any()))
                .thenReturn(new KnowledgeDocumentRepository.DocumentPage(List.of(document()), 1));

        mockMvc.perform(get("/api/v1/knowledge/documents").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("doc_1"))
                .andExpect(jsonPath("$.data[0].currentVersionId").value("docv_1"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].publicId").doesNotExist());

        verify(security).requirePermission(PermissionCode.KNOWLEDGE_READ);
    }

    @Test
    void documentDetailIncludesImmutableVersions() throws Exception {
        when(documents.findDocument("doc_1", false)).thenReturn(Optional.of(document()));
        when(documents.listVersions("doc_1")).thenReturn(List.of(version()));

        mockMvc.perform(get("/api/v1/knowledge/documents/doc_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("doc_1"))
                .andExpect(jsonPath("$.data.currentVersionId").value("docv_1"))
                .andExpect(jsonPath("$.data.metadata").isMap())
                .andExpect(jsonPath("$.data.versions[0].id").value("docv_1"))
                .andExpect(jsonPath("$.data.versions[0].documentId").value("doc_1"));
    }

    @Test
    void deleteRequiresWritePermissionAndIfMatch() throws Exception {
        when(commands.softDelete(eq("doc_1"), eq(3L), eq("obsolete"), any())).thenReturn(MutationOutcome.UPDATED);
        when(documents.findDocument("doc_1", true)).thenReturn(Optional.of(deletedDocument()));
        when(documents.listVersions("doc_1")).thenReturn(List.of(version()));

        mockMvc.perform(delete("/api/v1/knowledge/documents/doc_1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"obsolete\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.id").value("doc_1"));

        verify(security).requirePermission(PermissionCode.KNOWLEDGE_DELETE);
    }

    @Test
    void deleteVersionConflictUsesStableErrorCode() throws Exception {
        when(commands.softDelete(eq("doc_1"), eq(3L), isNull(), any())).thenReturn(MutationOutcome.VERSION_CONFLICT);

        mockMvc.perform(delete("/api/v1/knowledge/documents/doc_1").header("If-Match", "3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_CONFLICT"));
    }

    @Test
    void restoreReturnsFlatUpdatedDocumentDetail() throws Exception {
        when(commands.restore(eq("doc_1"), eq(4L), any())).thenReturn(MutationOutcome.UPDATED);
        when(documents.findDocument("doc_1", false)).thenReturn(Optional.of(restoredDocument()));
        when(documents.listVersions("doc_1")).thenReturn(List.of(version()));

        mockMvc.perform(post("/api/v1/knowledge/documents/doc_1/restore").header("If-Match", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("doc_1"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(5))
                .andExpect(jsonPath("$.data.versions[0].id").value("docv_1"));
    }

    @Test
    void multipartImportReturnsAcceptedTaskReference() throws Exception {
        KnowledgeImportRecord record = importRecord();
        when(submissions.submit(any(), any()))
                .thenReturn(new KnowledgeImportSubmissionService.Submission(record, "tsk_1"));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "knowledge.jsonl",
                "application/x-ndjson",
                "{\"title\":\"Node\",\"content\":\"guide\"}\n".getBytes());

        mockMvc.perform(multipart("/api/v1/knowledge/imports")
                        .file(file)
                        .param("importType", "JSONL")
                        .param("duplicatePolicy", "SKIP")
                        .param("dryRun", "false")
                        .param("metadata", "{\"owner\":\"sre\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.importId").value("imp_1"))
                .andExpect(jsonPath("$.data.taskId").value("tsk_1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void importDetailUsesSafeProjectionAndReadPermission() throws Exception {
        when(imports.find("imp_1")).thenReturn(Optional.of(importRecord()));

        mockMvc.perform(get("/api/v1/knowledge/imports/imp_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("imp_1"))
                .andExpect(jsonPath("$.data.taskId").value("tsk_1"))
                .andExpect(jsonPath("$.data.publicId").doesNotExist());

        verify(security).requirePermission(PermissionCode.KNOWLEDGE_READ);
    }

    @Test
    void importListUsesFrontendIdAndTaskIdFields() throws Exception {
        when(imports.list(any())).thenReturn(new KnowledgeImportRepository.ImportPage(List.of(importRecord()), 1));

        mockMvc.perform(get("/api/v1/knowledge/imports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("imp_1"))
                .andExpect(jsonPath("$.data[0].taskId").value("tsk_1"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    private static KnowledgeDocumentRecord document() {
        return new KnowledgeDocumentRecord(
                "doc_1",
                "external-1",
                "Node guide",
                "JSONL",
                "minio://knowledge/source.jsonl#line=1",
                "2026-07",
                "ACTIVE",
                Map.of(),
                "docv_1",
                3,
                Instant.parse("2026-07-21T01:00:00Z"),
                Instant.parse("2026-07-21T01:00:00Z"),
                null,
                null);
    }

    private static KnowledgeDocumentRecord deletedDocument() {
        KnowledgeDocumentRecord active = document();
        return new KnowledgeDocumentRecord(
                active.publicId(),
                active.externalDocumentId(),
                active.title(),
                active.sourceType(),
                active.sourceUri(),
                active.datasetVersion(),
                "DELETED",
                active.metadata(),
                active.currentVersionPublicId(),
                4,
                active.createdAt(),
                Instant.parse("2026-07-21T02:00:00Z"),
                Instant.parse("2026-07-21T02:00:00Z"),
                "obsolete");
    }

    private static KnowledgeDocumentRecord restoredDocument() {
        KnowledgeDocumentRecord active = document();
        return new KnowledgeDocumentRecord(
                active.publicId(),
                active.externalDocumentId(),
                active.title(),
                active.sourceType(),
                active.sourceUri(),
                active.datasetVersion(),
                "ACTIVE",
                active.metadata(),
                active.currentVersionPublicId(),
                5,
                active.createdAt(),
                Instant.parse("2026-07-21T03:00:00Z"),
                null,
                null);
    }

    private static KnowledgeDocumentVersionRecord version() {
        return new KnowledgeDocumentVersionRecord(
                "docv_1",
                "doc_1",
                "imp_1",
                1,
                "a".repeat(64),
                "application/json",
                20,
                "knowledge",
                "source.jsonl",
                null,
                "external-1",
                "INDEXED",
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-07-21T01:00:00Z"),
                Instant.parse("2026-07-21T01:00:00Z"));
    }

    private static KnowledgeImportRecord importRecord() {
        return new KnowledgeImportRecord(
                "imp_1",
                "tsk_1",
                "JSONL",
                "SKIP",
                false,
                "PENDING",
                "knowledge",
                "imports/source.jsonl",
                "b".repeat(64),
                100,
                "2026-07",
                1L,
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                Instant.parse("2026-07-21T01:00:00Z"),
                Instant.parse("2026-07-21T01:00:00Z"));
    }

    private static V1Principal principal() {
        UserAccount user = new UserAccount(
                7,
                "usr_7",
                "operator",
                "Operator",
                null,
                "hash",
                "ARGON2ID",
                1,
                "ACTIVE",
                1,
                Instant.now(),
                null,
                null,
                0,
                Set.of("OPERATOR"),
                Set.of(PermissionCode.KNOWLEDGE_READ, PermissionCode.KNOWLEDGE_WRITE, PermissionCode.KNOWLEDGE_DELETE));
        return new V1Principal(user, user.permissions(), V1Principal.AuthMethod.SESSION);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
