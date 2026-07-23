package com.kubeoncall.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.task.AsyncTaskRepository;

class KnowledgeImportSubmissionServiceTest {

    @Test
    void rejectsOversizedJsonlBeforeWritingAnObjectOrCreatingFacts() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setJsonlMaxPayloadBytes(128);
        KnowledgeObjectStorageService storage = mock(KnowledgeObjectStorageService.class);
        KnowledgeImportSubmissionService service = new KnowledgeImportSubmissionService(
                storage,
                mock(AsyncTaskRepository.class),
                mock(KnowledgeImportRepository.class),
                mock(OperationAuditWriter.class),
                mock(OutboxWriter.class),
                properties,
                mock(PlatformTransactionManager.class));

        assertThatThrownBy(() -> service.submit(
                        new KnowledgeImportSubmissionService.Upload(
                                "oversized.jsonl", new byte[129], "SKIP", false, null),
                        new KnowledgeImportSubmissionService.Actor(1L, "tester", "req_large_import", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSONL file exceeds the configured byte limit");
        verifyNoInteractions(storage);
    }
}
