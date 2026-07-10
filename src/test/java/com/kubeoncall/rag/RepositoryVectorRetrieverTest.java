package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryVectorRetrieverTest {

    @Test
    void shouldPassQueryEmbeddingToElasticsearchRepository() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setEsKnnEnabled(true);
        properties.getRag().setEmbeddingEnabled(true);
        properties.getRag().setVectorBackend("es");
        RetrievalRequest request = new RetrievalRequest("cpu high", Map.of(), 3);
        List<Double> vector = List.of(0.1, 0.2, 0.3);
        KnowledgeDocument document = new KnowledgeDocument(
                "doc-1", "CPU", "runbook", "manual", Map.of(), Instant.now());
        when(embeddingService.embed("cpu high"))
                .thenReturn(new EmbeddingService.EmbeddingResult(vector, "mock-provider", false));
        when(repository.searchVector(request, 3, vector)).thenReturn(List.of(document));
        RepositoryVectorRetriever retriever = new RepositoryVectorRetriever(repository, embeddingService, properties);

        List<KnowledgeDocument> result = retriever.retrieve(request, 3);

        assertTrue(retriever.available());
        verify(repository).searchVector(request, 3, vector);
        assertEquals("mock-provider", result.get(0).metadata().get("embedding_provider"));
    }

    @Test
    void shouldBeUnavailableForExternalBackend() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setEsKnnEnabled(true);
        properties.getRag().setVectorBackend("external");

        RepositoryVectorRetriever retriever = new RepositoryVectorRetriever(
                mock(KnowledgeRepository.class), mock(EmbeddingService.class), properties);

        assertEquals(false, retriever.available());
    }

    @Test
    void shouldActivateOnlyExternalHttpRetrieverForExternalBackend() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setVectorBackend("external");
        properties.getRag().setVectorEndpoint("http://vector/search");
        HttpVectorRetrievalClient external = new HttpVectorRetrievalClient(
                mock(ToolHttpClient.class), properties);
        RepositoryVectorRetriever local = new RepositoryVectorRetriever(
                mock(KnowledgeRepository.class), mock(EmbeddingService.class), properties);

        assertTrue(external.available());
        assertEquals(false, local.available());
    }
}
