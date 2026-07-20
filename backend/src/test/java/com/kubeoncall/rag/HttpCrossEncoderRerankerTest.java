package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.tool.http.ToolHttpClient;

class HttpCrossEncoderRerankerTest {

    @Test
    void shouldUseStandardRerankContractAndMapScoresByIndex() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setCrossEncoderEnabled(true);
        properties.getRag().setCrossEncoderEndpoint("http://rerank/v1/rerank");
        properties.getRag().setCrossEncoderApiKey("rerank-key");
        properties.getRag().setCrossEncoderModel("rerank-model-a");
        properties.getRag().setRerankTopN(2);
        when(httpClient.post(eq("http://rerank/v1/rerank"), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status",
                        "success",
                        "response",
                        Map.of(
                                "results",
                                List.of(
                                        Map.of("index", 1, "relevance_score", 0.9),
                                        Map.of("index", 0, "relevance_score", 0.2)))));
        HttpCrossEncoderReranker reranker = new HttpCrossEncoderReranker(httpClient, properties);
        List<KnowledgeDocument> documents = List.of(
                new KnowledgeDocument("doc-a", "A", "content A", "manual", Map.of(), Instant.now()),
                new KnowledgeDocument("doc-b", "B", "content B", "manual", Map.of(), Instant.now()));

        Map<String, Double> scores = reranker.score("cpu", documents);

        assertEquals(Map.of("doc-b", 0.9, "doc-a", 0.2), scores);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .post(eq("http://rerank/v1/rerank"), bodyCaptor.capture(), anyInt(), headersCaptor.capture(), anyMap());
        assertEquals("rerank-model-a", bodyCaptor.getValue().get("model"));
        assertEquals("cpu", bodyCaptor.getValue().get("query"));
        assertEquals(2, bodyCaptor.getValue().get("top_n"));
        assertEquals(
                List.of("A\ncontent A", "B\ncontent B"), bodyCaptor.getValue().get("documents"));
        assertEquals("Bearer rerank-key", headersCaptor.getValue().get("Authorization"));
    }

    @Test
    void shouldReadDashScopeOutputEnvelope() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setCrossEncoderEnabled(true);
        properties
                .getRag()
                .setCrossEncoderEndpoint(
                        "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank");
        when(httpClient.post(any(), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status",
                        "success",
                        "response",
                        Map.of("output", Map.of("results", List.of(Map.of("index", 0, "relevance_score", 0.8))))));
        List<KnowledgeDocument> documents =
                List.of(new KnowledgeDocument("doc-a", "A", "content A", "manual", Map.of(), Instant.now()));

        assertEquals(
                Map.of("doc-a", 0.8), new HttpCrossEncoderReranker(httpClient, properties).score("cpu", documents));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).post(any(), bodyCaptor.capture(), anyInt(), anyMap(), anyMap());
        assertEquals("cross-encoder", bodyCaptor.getValue().get("model"));
        assertEquals(
                Map.of("query", "cpu", "documents", List.of("A\ncontent A")),
                bodyCaptor.getValue().get("input"));
        assertEquals(
                Map.of("top_n", 1, "return_documents", false),
                bodyCaptor.getValue().get("parameters"));
    }

    @Test
    void shouldExposeSafeProviderFailureForFallbackAudit() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setCrossEncoderEnabled(true);
        properties.getRag().setCrossEncoderEndpoint("http://rerank/v1/rerank");
        when(httpClient.post(any(), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of("status", "failed", "errorType", "TimeoutError", "httpStatus", 500));
        KnowledgeDocument document =
                new KnowledgeDocument("doc-a", "A", "content A", "manual", Map.of(), Instant.now());

        RagProviderException failure =
                assertThrows(RagProviderException.class, () -> new HttpCrossEncoderReranker(httpClient, properties)
                        .score("cpu", List.of(document)));

        assertTrue(failure.getMessage().contains("TimeoutError"));
        assertTrue(failure.getMessage().contains("httpStatus=500"));
    }
}
