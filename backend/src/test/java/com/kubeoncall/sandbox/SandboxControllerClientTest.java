package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.observability.DependencyCircuitBreaker;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SandboxControllerClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signsServerDerivedRequestAndReturnsOnlyExpectedLifecycleFields() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<Map<String, java.util.List<String>>> capturedHeaders = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            capturedHeaders.set(exchange.getRequestHeaders());
            respond(exchange, 200, "{\"runId\":\"sbx_abc\",\"phase\":\"PENDING\",\"untrusted\":true}");
        });
        SandboxControllerClient client = client(500);

        SandboxControllerClient.DispatchResult result = client.dispatch(run());

        assertThat(result.controllerRunId()).isEqualTo("sbx_abc");
        assertThat(result.phase()).isEqualTo("PENDING");
        assertThat(new String(capturedBody.get(), StandardCharsets.UTF_8))
                .contains("\"runId\":\"sbx_abc\"")
                .contains("minio://sandbox-artifacts/sandbox/sbx_abc/inputs/evidence.json")
                .doesNotContain("runtimeImageDigest");
        Map<String, java.util.List<String>> headers = capturedHeaders.get();
        String canonical = "POST\n/internal/v1/runs\n" + first(headers, "X-sandbox-timestamp") + "\n"
                + first(headers, "X-sandbox-nonce") + "\n"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(capturedBody.get()));
        assertThat(first(headers, "X-sandbox-signature")).isEqualTo(hmac(canonical, "test-secret"));
        assertThat(first(headers, "X-sandbox-key-id")).isEqualTo("backend-test");
    }

    @Test
    void treatsTimeoutAsRetryableWithoutLeakingResponseBody() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"runId\":\"sbx_abc\"}");
        });
        SandboxControllerClient client = client(30);

        assertThatThrownBy(() -> client.dispatch(run()))
                .isInstanceOf(SandboxControllerClientException.class)
                .satisfies(error -> {
                    SandboxControllerClientException failure = (SandboxControllerClientException) error;
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.getMessage()).isEqualTo("CONTROLLER_TIMEOUT");
                });
    }

    @Test
    void failsFastForPermanentControllerRejectionAndOpensCircuitForTransientFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            int count = calls.incrementAndGet();
            respond(exchange, count == 1 ? 400 : 503, "secret-response-body-must-not-leak");
        });
        SandboxControllerClient permanentClient = client(500);

        assertThatThrownBy(() -> permanentClient.dispatch(run()))
                .isInstanceOf(SandboxControllerClientException.class)
                .satisfies(error -> {
                    SandboxControllerClientException failure = (SandboxControllerClientException) error;
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).isEqualTo("CONTROLLER_HTTP_400");
                    assertThat(failure.getMessage()).doesNotContain("secret-response");
                });
        // A permanent 4xx is still a dependency failure for this generic breaker. Reset the test
        // client so this branch verifies the transient open/reject path independently.
        SandboxControllerClient transientClient = client(500);
        assertThatThrownBy(() -> transientClient.dispatch(run())).hasMessage("CONTROLLER_HTTP_503");
        assertThatThrownBy(() -> transientClient.dispatch(run()))
                .isInstanceOf(DependencyCircuitBreaker.CircuitOpenException.class);
        assertThat(calls).hasValue(2);
    }

    private SandboxControllerClient client(long readTimeoutMillis) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties
                .getSandbox()
                .setControllerEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getSandbox().setControllerKeyId("backend-test");
        properties.getSandbox().setControllerHmacSecret("test-secret");
        properties.getSandbox().setControllerConnectTimeoutMillis(500);
        properties.getSandbox().setControllerReadTimeoutMillis(readTimeoutMillis);
        properties.getSandbox().setControllerMaxResponseBytes(1024);
        properties.getStorage().getMinio().setBucket("sandbox-artifacts");
        properties.getDependencyCircuitBreaker().setFailureThreshold(1);
        return new SandboxControllerClient(
                properties, new DependencyCircuitBreaker(properties, metrics()), new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private static KubeOnCallMetricsService metrics() {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        return new KubeOnCallMetricsService(provider);
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/runs", handler::handle);
        server.start();
    }

    private static SandboxRunRecord run() {
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                null,
                null,
                com.kubeoncall.sandbox.domain.SandboxRunMode.FIXED_DIAGNOSTIC,
                "pod-inspect",
                "v1",
                "registry.example/tool@sha256:" + "a".repeat(64),
                com.kubeoncall.sandbox.domain.SandboxRunStatus.DISPATCHING,
                com.kubeoncall.sandbox.domain.SandboxCleanupStatus.NOT_REQUIRED,
                null,
                0,
                com.kubeoncall.sandbox.domain.SandboxRiskLevel.LOW,
                "usr_1",
                "key",
                Map.of(),
                Map.of(),
                null,
                null,
                "sbx_abc",
                "worker-a",
                Instant.now().plusSeconds(60),
                1L,
                1,
                3,
                Instant.now().plusSeconds(600),
                "req_abc",
                null,
                2L,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now());
    }

    private static String first(Map<String, java.util.List<String>> headers, String name) {
        return headers.get(name).get(0);
    }

    private static String hmac(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
