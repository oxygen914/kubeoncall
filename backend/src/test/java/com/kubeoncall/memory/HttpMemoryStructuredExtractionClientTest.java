package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

class HttpMemoryStructuredExtractionClientTest {

    @Test
    void shouldParseOpenAiCompatibleStructuredResponse() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setLlmExtractionEnabled(true);
        properties.getMemory().setLlmExtractionEndpoint("http://llm/v1/chat/completions");
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(any(), any(), any(Integer.class), any(), any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "success",
                                "response",
                                Map.of(
                                        "choices",
                                        List.of(
                                                Map.of(
                                                        "message",
                                                        Map.of(
                                                                "content",
                                                                "{\"memories\":[{\"memoryType\":\"INCIDENT_SUMMARY\",\"scope\":\"FINGERPRINT\",\"subject\":\"OOM\",\"content\":\"Raised memory limit after verifying metrics\",\"evidence\":[\"metrics\"],\"confidence\":0.8}]}"))))));
        HttpMemoryStructuredExtractionClient client =
                new HttpMemoryStructuredExtractionClient(httpClient, new ObjectMapper(), properties);

        Optional<List<MemoryExtractionCandidate>> result = client.extract("system", "user");

        assertTrue(result.isPresent());
        assertEquals(1, result.orElseThrow().size());
        assertEquals(MemoryType.INCIDENT_SUMMARY, result.orElseThrow().get(0).memoryType());
        assertEquals(0.8d, result.orElseThrow().get(0).confidence());
    }

    @Test
    void shouldTreatMalformedLlmOutputAsUnavailableForFallback() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setLlmExtractionEnabled(true);
        properties.getMemory().setLlmExtractionEndpoint("http://llm/v1/chat/completions");
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(any(), any(), any(Integer.class), any(), any()))
                .thenReturn(Map.of("status", "success", "response", Map.of("output_text", "not structured JSON")));
        HttpMemoryStructuredExtractionClient client =
                new HttpMemoryStructuredExtractionClient(httpClient, new ObjectMapper(), properties);

        assertTrue(client.extract("system", "user").isEmpty());
    }
}
