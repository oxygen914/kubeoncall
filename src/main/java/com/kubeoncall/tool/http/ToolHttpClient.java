package com.kubeoncall.tool.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ToolHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ToolHttpClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ToolHttpClient(ObjectMapper objectMapper) {
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> post(
            String endpoint, Map<String, Object> body, int timeoutMillis, Map<String, Object> metadata) {
        return post(endpoint, body, timeoutMillis, Map.of(), metadata);
    }

    public Map<String, Object> post(
            String endpoint,
            Map<String, Object> body,
            int timeoutMillis,
            Map<String, String> headers,
            Map<String, Object> metadata) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (metadata != null) {
            result.putAll(metadata);
        }

        if (endpoint == null || endpoint.isBlank()) {
            result.put("status", "failed");
            result.put("httpStatus", 500);
            result.put("errorType", "ConfigurationError");
            result.put("errorMessage", "Endpoint is not configured");
            return result;
        }

        long startedAt = System.currentTimeMillis();
        try {
            String jsonBody = objectMapper.writeValueAsString(body == null ? Map.of() : body);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMillis)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                        requestBuilder.header(name, value);
                    }
                });
            }
            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            long latencyMs = System.currentTimeMillis() - startedAt;

            result.put("status", statusCode >= 200 && statusCode < 300 ? "success" : "failed");
            result.put("httpStatus", statusCode);
            result.put("latencyMs", latencyMs);
            result.put("endpoint", endpoint);
            result.put("response", parseBody(response.body()));
            return result;
        } catch (Exception ex) {
            result.put("status", "failed");
            result.put("httpStatus", 500);
            result.put("latencyMs", System.currentTimeMillis() - startedAt);
            result.put("endpoint", endpoint);
            result.put("errorType", ex.getClass().getSimpleName());
            result.put("errorMessage", ex.getMessage());
            return result;
        }
    }

    private Object parseBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(responseBody, MAP_TYPE);
        } catch (Exception ex) {
            log.debug(
                    "Tool HTTP response is not JSON; returning its text representation: errorType={}",
                    ex.getClass().getSimpleName());
            return responseBody;
        }
    }
}
