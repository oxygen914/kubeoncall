package com.kubeoncall.tool.http;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ToolHttpClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> post(
            String endpoint, Map<String, Object> body, int timeoutMillis, Map<String, Object> metadata) {
        return post(endpoint, body, timeoutMillis, Map.of(), metadata);
    }

    public Map<String, Object> get(
            String endpoint,
            Map<String, ?> queryParameters,
            int timeoutMillis,
            Map<String, String> headers,
            Map<String, Object> metadata,
            int maxResponseBytes) {
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
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri(endpoint, queryParameters))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMillis)))
                    .header("Accept", "application/json")
                    .GET();
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                        requestBuilder.header(name, value);
                    }
                });
            }
            HttpResponse<InputStream> response =
                    httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int statusCode = response.statusCode();
            boolean successful = statusCode >= 200 && statusCode < 300;
            result.put("status", successful ? "success" : "failed");
            result.put("httpStatus", statusCode);
            result.put("latencyMs", System.currentTimeMillis() - startedAt);
            result.put("responseHeaders", responseHeaders(response));
            try (InputStream responseBody = response.body()) {
                if (successful) {
                    result.put("response", parseResponse(response, responseBody, Math.max(1024, maxResponseBytes)));
                } else {
                    result.put("errorType", "HttpStatusError");
                    result.put("errorMessage", "Tool endpoint returned HTTP " + statusCode);
                }
            }
            return result;
        } catch (ResponseTooLargeException ex) {
            result.put("status", "failed");
            result.put("httpStatus", 502);
            result.put("latencyMs", System.currentTimeMillis() - startedAt);
            result.put("errorType", "ResponseTooLarge");
            result.put("errorMessage", "Tool response exceeds the configured byte limit");
            return result;
        } catch (Exception ex) {
            log.warn("Tool HTTP request failed: errorType={}", ex.getClass().getSimpleName());
            result.put("status", "failed");
            result.put("httpStatus", 500);
            result.put("latencyMs", System.currentTimeMillis() - startedAt);
            boolean timedOut = ex instanceof HttpTimeoutException;
            result.put("errorType", timedOut ? "TimeoutError" : "ToolTransportError");
            result.put("errorMessage", timedOut ? "Tool request timed out" : "Tool request failed");
            return result;
        }
    }

    private URI uri(String endpoint, Map<String, ?> queryParameters) {
        if (queryParameters == null || queryParameters.isEmpty()) {
            return URI.create(endpoint);
        }
        StringBuilder value = new StringBuilder(endpoint);
        value.append(endpoint.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, ?> entry : queryParameters.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!first) {
                value.append('&');
            }
            first = false;
            value.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            value.append('=');
            value.append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return URI.create(value.toString());
    }

    public Map<String, Object> post(
            String endpoint,
            Map<String, Object> body,
            int timeoutMillis,
            Map<String, String> headers,
            Map<String, Object> metadata) {
        return post(endpoint, body, timeoutMillis, headers, metadata, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public Map<String, Object> post(
            String endpoint,
            Map<String, Object> body,
            int timeoutMillis,
            Map<String, String> headers,
            Map<String, Object> metadata,
            int maxResponseBytes) {
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

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int statusCode = response.statusCode();
            long latencyMs = System.currentTimeMillis() - startedAt;

            boolean successful = statusCode >= 200 && statusCode < 300;
            result.put("status", successful ? "success" : "failed");
            result.put("httpStatus", statusCode);
            result.put("latencyMs", latencyMs);
            result.put("responseHeaders", responseHeaders(response));
            try (InputStream responseBody = response.body()) {
                if (successful) {
                    int boundedBytes = Math.max(1024, maxResponseBytes);
                    result.put("response", parseResponse(response, responseBody, boundedBytes));
                } else {
                    result.put("errorType", "HttpStatusError");
                    result.put("errorMessage", "Tool endpoint returned HTTP " + statusCode);
                }
            }
            return result;
        } catch (ResponseTooLargeException ex) {
            result.put("status", "failed");
            result.put("httpStatus", 502);
            result.put("latencyMs", System.currentTimeMillis() - startedAt);
            result.put("errorType", "ResponseTooLarge");
            result.put("errorMessage", "Tool response exceeds the configured byte limit");
            return result;
        } catch (Exception ex) {
            log.warn("Tool HTTP request failed: errorType={}", ex.getClass().getSimpleName());
            result.put("status", "failed");
            result.put("httpStatus", 500);
            result.put("latencyMs", System.currentTimeMillis() - startedAt);
            boolean timedOut = ex instanceof HttpTimeoutException;
            result.put("errorType", timedOut ? "TimeoutError" : "ToolTransportError");
            result.put("errorMessage", timedOut ? "Tool request timed out" : "Tool request failed");
            return result;
        }
    }

    private Map<String, String> responseHeaders(HttpResponse<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values));
            }
        });
        return Map.copyOf(headers);
    }

    private Object parseResponse(HttpResponse<?> response, InputStream responseBody, int maxBytes) throws Exception {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
            return parseEventStream(responseBody, maxBytes);
        }
        return parseBody(readBounded(responseBody, maxBytes));
    }

    private String readBounded(InputStream responseBody, int maxBytes) throws Exception {
        return new String(new BoundedInputStream(responseBody, maxBytes).readAllBytes(), StandardCharsets.UTF_8);
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

    private Object parseEventStream(InputStream responseBody, int maxBytes) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BoundedInputStream(responseBody, maxBytes), StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                Object event = parseSseData(data);
                data.setLength(0);
                if (event != null) {
                    return event;
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).stripLeading());
            }
        }
        Object event = parseSseData(data);
        return event == null ? Map.of() : event;
    }

    private Object parseSseData(StringBuilder data) {
        if (data.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> event = objectMapper.readValue(data.toString(), MAP_TYPE);
            if (event.get("id") == null && event.get("method") != null) {
                return null;
            }
            return event;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private int remaining;

        private BoundedInputStream(InputStream input, int maxBytes) {
            super(input);
            remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                if (super.read() >= 0) {
                    throw new ResponseTooLargeException();
                }
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return read() < 0 ? -1 : 1;
            }
            int read = super.read(bytes, offset, Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }

    private static final class ResponseTooLargeException extends IOException {}
}
