package com.kubeoncall.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.tool.http.ToolHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class ToolHttpClientIT {

    private HttpServer server;
    private ToolHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/success", exchange -> respond(exchange, 200, "{\"healthy\":true}"));
        server.createContext("/failure", exchange -> respond(exchange, 503, "credential=secret"));
        server.createContext("/plain-text", exchange -> respond(exchange, 200, "ready", "text/plain"));
        server.createContext("/authorized", exchange -> {
            boolean authorized = "Bearer integration-token"
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, authorized ? 200 : 401, authorized ? "{\"authorized\":true}" : "token=secret");
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(750);
                respond(exchange, 200, "{\"late\":true}");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        client = new ToolHttpClient(HttpClient.newHttpClient(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnParsedPayloadForSuccessfulToolResponse() {
        Map<String, Object> result = client.post(endpoint("/success"), Map.of("query", "up"), 1000, Map.of());

        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        assertInstanceOf(Map.class, result.get("response"));
        assertEquals(true, ((Map<?, ?>) result.get("response")).get("healthy"));
    }

    @Test
    void shouldUseStableErrorFieldsForFailedToolResponse() {
        Map<String, Object> result = client.post(endpoint("/failure"), Map.of(), 1000, Map.of());

        assertEquals("failed", result.get("status"));
        assertEquals(503, result.get("httpStatus"));
        assertEquals("HttpStatusError", result.get("errorType"));
        assertEquals("Tool endpoint returned HTTP 503", result.get("errorMessage"));
        assertFalse(result.containsKey("response"));
    }

    @Test
    void shouldPreserveSuccessfulNonJsonToolResponseAsText() {
        Map<String, Object> result = client.post(endpoint("/plain-text"), Map.of(), 1000, Map.of());

        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        assertEquals("ready", result.get("response"));
    }

    @Test
    void shouldForwardConfiguredAuthenticationHeaders() {
        Map<String, Object> result = client.post(
                endpoint("/authorized"), Map.of(), 1000, Map.of("Authorization", "Bearer integration-token"), Map.of());

        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        assertEquals(true, ((Map<?, ?>) result.get("response")).get("authorized"));
    }

    @Test
    void shouldUseStableErrorFieldsWhenToolRequestTimesOut() {
        Map<String, Object> result = client.post(endpoint("/slow"), Map.of(), 500, Map.of());

        assertEquals("failed", result.get("status"));
        assertEquals(500, result.get("httpStatus"));
        assertEquals("TimeoutError", result.get("errorType"));
        assertEquals("Tool request timed out", result.get("errorMessage"));
        assertTrue(((Number) result.get("latencyMs")).longValue() >= 400);
    }

    private String endpoint(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, "application/json");
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
