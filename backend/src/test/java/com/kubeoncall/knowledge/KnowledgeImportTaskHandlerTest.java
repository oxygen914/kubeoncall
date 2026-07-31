package com.kubeoncall.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeDocumentRepository;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRecord;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.rag.KnowledgeJsonlImportService;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;

class KnowledgeImportTaskHandlerTest {

    private KnowledgeObjectStorageService objectStorage;
    private KnowledgeJsonlImportService jsonlImportService;
    private KnowledgeDocumentRepository documentRepository;
    private KnowledgeImportRepository importRepository;
    private AsyncTaskRepository taskRepository;
    private KnowledgeImportTaskHandler handler;

    @BeforeEach
    void setUp() {
        objectStorage = mock(KnowledgeObjectStorageService.class);
        jsonlImportService = mock(KnowledgeJsonlImportService.class);
        documentRepository = mock(KnowledgeDocumentRepository.class);
        importRepository = mock(KnowledgeImportRepository.class);
        taskRepository = mock(AsyncTaskRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setDocumentMaxContentBytes(1024 * 1024);
        handler = new KnowledgeImportTaskHandler(
                objectStorage,
                jsonlImportService,
                documentRepository,
                importRepository,
                taskRepository,
                new ObjectMapper(),
                properties);
    }

    @Test
    void invalidInputCompletesAsPartialAndWritesMinioErrorReport() throws Exception {
        String payload = "{\"title\":\"missing content\"}\n";
        KnowledgeImportRecord pending = importRecord(payload, "PENDING", 1);
        KnowledgeImportRecord running = importRecord(payload, "RUNNING", 2);
        when(importRepository.find("imp_1")).thenReturn(Optional.of(pending), Optional.of(running));
        when(objectStorage.readText(pending.sourceBucket(), pending.sourceObjectKey()))
                .thenReturn(payload);
        when(taskRepository.updateProgress(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(true);
        when(importRepository.updateProgress(
                        anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(true);
        when(objectStorage.storeErrorReport(anyString(), anyString()))
                .thenReturn(new StoredDocumentReference("errors.jsonl", "knowledge", true, "stored"));
        when(importRepository.complete(
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any()))
                .thenReturn(true);

        AsyncTaskHandler.HandlerResult result = handler.handle(context());

        assertThat(result.result())
                .containsEntry("processed", 1)
                .containsEntry("failed", 1)
                .containsEntry("succeeded", 0);
        verify(jsonlImportService, never()).importJsonl(anyString());
        verify(objectStorage).storeErrorReport(anyString(), anyString());
    }

    @Test
    void validLineIndexFailureIsRethrownForGenericWorkerRetry() throws Exception {
        String payload = "{\"doc_id\":\"node-1\",\"title\":\"Node\",\"content\":\"guide\"}\n";
        KnowledgeImportRecord pending = importRecord(payload, "PENDING", 1);
        when(importRepository.find("imp_1")).thenReturn(Optional.of(pending));
        when(objectStorage.readText(pending.sourceBucket(), pending.sourceObjectKey()))
                .thenReturn(payload);
        when(documentRepository.findDocumentByExternalId("JSONL", "node-1", true))
                .thenReturn(Optional.empty());
        when(jsonlImportService.importJsonl(anyString()))
                .thenReturn(new KnowledgeJsonlImportService.ImportResult(
                        1,
                        0,
                        1,
                        List.of(new KnowledgeJsonlImportService.LineResult(
                                1, "failed", null, "Invalid JSONL document"))));

        assertThatThrownBy(() -> handler.handle(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indexing failed");

        verify(importRepository, never())
                .complete(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
        verify(taskRepository, never())
                .updateProgress(anyString(), anyString(), anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void validLineIndexesAndCreatesMysqlDocumentVersionBeforeCompletion() throws Exception {
        String payload = "{\"doc_id\":\"node-1\",\"title\":\"Node\",\"content\":\"guide\"}\n";
        KnowledgeImportRecord pending = importRecord(payload, "PENDING", 1);
        KnowledgeImportRecord running = importRecord(payload, "RUNNING", 2);
        when(importRepository.find("imp_1")).thenReturn(Optional.of(pending), Optional.of(running));
        when(objectStorage.readText(pending.sourceBucket(), pending.sourceObjectKey()))
                .thenReturn(payload);
        when(documentRepository.findDocumentByExternalId("JSONL", "node-1", true))
                .thenReturn(Optional.empty());
        when(jsonlImportService.importJsonl(anyString()))
                .thenReturn(new KnowledgeJsonlImportService.ImportResult(
                        1,
                        1,
                        0,
                        List.of(new KnowledgeJsonlImportService.LineResult(1, "imported", "node-1", "success"))));
        when(documentRepository.createDocument(any())).thenReturn(document());
        when(taskRepository.updateProgress(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(true);
        when(importRepository.updateProgress(
                        anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(true);
        when(importRepository.complete(
                        anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), isNull(), isNull(), any()))
                .thenReturn(true);

        AsyncTaskHandler.HandlerResult result = handler.handle(context());

        assertThat(result.result())
                .containsEntry("processed", 1)
                .containsEntry("succeeded", 1)
                .containsEntry("failed", 0);
        verify(documentRepository).createDocument(any());
        verify(documentRepository).createVersion(any());
        verify(importRepository)
                .complete(
                        anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), isNull(), isNull(), any());
    }

    @Test
    void minioReadFailureIsRethrownForGenericWorkerRetry() throws Exception {
        String payload = "{\"title\":\"Node\",\"content\":\"guide\"}\n";
        KnowledgeImportRecord pending = importRecord(payload, "PENDING", 1);
        when(importRepository.find("imp_1")).thenReturn(Optional.of(pending));
        when(objectStorage.readText(pending.sourceBucket(), pending.sourceObjectKey()))
                .thenThrow(new IllegalStateException("MinIO unavailable"));

        assertThatThrownBy(() -> handler.handle(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MinIO unavailable");

        verify(jsonlImportService, never()).importJsonl(anyString());
    }

    private AsyncTaskContext context() throws Exception {
        Constructor<AsyncTaskContext> constructor = AsyncTaskContext.class.getDeclaredConstructor(
                AsyncTaskRecord.class, String.class, BooleanSupplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance(task(), "owner-1", (BooleanSupplier) () -> true);
    }

    private AsyncTaskRecord task() {
        return new AsyncTaskRecord(
                1,
                "tsk_1",
                "KNOWLEDGE_IMPORT",
                "KNOWLEDGE_IMPORT",
                "imp_1",
                "dedupe",
                "RUNNING",
                "indexing",
                0,
                Map.of(),
                Map.of(),
                null,
                null,
                "owner-1",
                Instant.now().plusSeconds(60),
                1,
                1,
                5,
                Instant.now(),
                Instant.now(),
                null,
                "req_1",
                null,
                1,
                Instant.now(),
                Instant.now());
    }

    private KnowledgeDocumentRecord document() {
        return new KnowledgeDocumentRecord(
                "doc_node_1",
                "node-1",
                "Node",
                "JSONL",
                "minio://knowledge/imports/source.jsonl#line=1",
                null,
                "ACTIVE",
                Map.of(),
                null,
                1,
                Instant.now(),
                Instant.now(),
                null,
                null);
    }

    private KnowledgeImportRecord importRecord(String payload, String status, long version) {
        return new KnowledgeImportRecord(
                "imp_1",
                "tsk_1",
                "JSONL",
                "SKIP",
                false,
                status,
                "knowledge",
                "imports/source.jsonl",
                sha256(payload),
                payload.getBytes(StandardCharsets.UTF_8).length,
                null,
                1L,
                status.equals("RUNNING") ? 1 : 0,
                0,
                status.equals("RUNNING") ? 1 : 0,
                0,
                null,
                null,
                null,
                null,
                status.equals("RUNNING") ? Instant.now() : null,
                null,
                version,
                Instant.now(),
                Instant.now());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
