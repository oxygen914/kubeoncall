package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    @Test
    void shouldPersistMemoryAsIsolatedKnowledgeDocument() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        MemoryService service = new MemoryService(repository, new KubeOnCallProperties());
        MemoryEntry entry = new MemoryEntry(
                "memory-1",
                MemoryType.SERVICE_FACT,
                MemoryScope.SERVICE,
                "payment owner",
                "payment-service is owned by team-payments",
                "payment-service",
                null,
                null,
                Instant.now(),
                Instant.now(),
                Map.of("env", "prod")
        );

        service.remember(entry);

        ArgumentCaptor<KnowledgeDocument> documentCaptor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(documentCaptor.capture());
        KnowledgeDocument document = documentCaptor.getValue();
        assertEquals("memory", document.source());
        assertEquals("memory", document.metadata().get("source_type"));
        assertEquals("SERVICE_FACT", document.metadata().get("memory_type"));
        assertEquals("SERVICE", document.metadata().get("memory_scope"));
        assertEquals("payment-service", document.metadata().get("service"));
    }

    @Test
    void shouldSearchOnlyMemoryDocuments() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        MemoryService service = new MemoryService(repository, new KubeOnCallProperties());
        KnowledgeDocument document = new KnowledgeDocument(
                "memory-2",
                "payment pitfall",
                "avoid restart before checking queue lag",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "KNOWN_PITFALL",
                        "memory_scope", "SERVICE",
                        "service", "payment-service"
                ),
                Instant.now()
        );
        when(repository.searchLexical(any(RetrievalRequest.class), eq(2))).thenReturn(List.of(document));

        List<MemoryEntry> entries = service.search("payment restart", Map.of("service", "payment-service"), 2);

        assertEquals(1, entries.size());
        assertEquals(MemoryType.KNOWN_PITFALL, entries.get(0).type());
        ArgumentCaptor<RetrievalRequest> requestCaptor = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(repository).searchLexical(requestCaptor.capture(), eq(2));
        assertEquals("memory", requestCaptor.getValue().filters().get("source_type"));
        assertEquals("payment-service", requestCaptor.getValue().filters().get("service"));
    }
}
