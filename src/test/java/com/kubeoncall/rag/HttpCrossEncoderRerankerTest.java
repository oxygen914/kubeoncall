package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpCrossEncoderRerankerTest {

    @Test
    void shouldUseStandardRerankContractAndMapScoresByIndex() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setCrossEncoderEnabled(true);
        properties.getRag().setCrossEncoderEndpoint("http://rerank/v1/rerank");
        properties.getRag().setCrossEncoderModel("rerank-model-a");
        properties.getRag().setRerankTopN(2);
        when(httpClient.post(eq("http://rerank/v1/rerank"), anyMap(), anyInt(), anyMap()))
                .thenReturn(Map.of(
                        "status", "success",
                        "response", Map.of("results", List.of(
                                Map.of("index", 1, "relevance_score", 0.9),
                                Map.of("index", 0, "relevance_score", 0.2)
                        ))
                ));
        HttpCrossEncoderReranker reranker = new HttpCrossEncoderReranker(httpClient, properties);
        List<KnowledgeDocument> documents = List.of(
                new KnowledgeDocument("doc-a", "A", "content A", "manual", Map.of(), Instant.now()),
                new KnowledgeDocument("doc-b", "B", "content B", "manual", Map.of(), Instant.now())
        );

        Map<String, Double> scores = reranker.score("cpu", documents);

        assertEquals(Map.of("doc-b", 0.9, "doc-a", 0.2), scores);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).post(eq("http://rerank/v1/rerank"), bodyCaptor.capture(), anyInt(), anyMap());
        assertEquals("rerank-model-a", bodyCaptor.getValue().get("model"));
        assertEquals("cpu", bodyCaptor.getValue().get("query"));
        assertEquals(2, bodyCaptor.getValue().get("top_n"));
        assertEquals(List.of("A\ncontent A", "B\ncontent B"), bodyCaptor.getValue().get("documents"));
    }
}
