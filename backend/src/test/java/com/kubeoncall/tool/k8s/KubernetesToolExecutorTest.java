package com.kubeoncall.tool.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.observability.DependencyCircuitBreaker;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.tool.http.ToolHttpClient;

class KubernetesToolExecutorTest {

    @Test
    void shouldSendConfiguredBearerTokenWithoutAddingItToMetadata() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getKubernetes().setEndpoint("http://adapter/api/tools/kubernetes");
        properties.getIntegrations().getKubernetes().setBearerToken("secret-token");
        when(httpClient.post(eq("http://adapter/api/tools/kubernetes"), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of("status", "success", "httpStatus", 200, "response", Map.of()));
        KubernetesToolExecutor executor = new KubernetesToolExecutor(httpClient, properties);

        executor.execute("queryEvents", Map.of("namespace", "kubeoncall-system"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .post(
                        eq("http://adapter/api/tools/kubernetes"),
                        anyMap(),
                        anyInt(),
                        headers.capture(),
                        metadata.capture());
        assertEquals("Bearer secret-token", headers.getValue().get("Authorization"));
        assertEquals("kubernetes", metadata.getValue().get("targetSystem"));
        assertFalse(metadata.getValue().toString().contains("secret-token"));
    }

    @Test
    void shouldFailClosedWhenIndependentMutationAdapterIsNotConfigured() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getKubernetes().setEndpoint("http://readonly/api/tools/kubernetes");
        KubernetesToolExecutor executor = new KubernetesToolExecutor(httpClient, properties);

        Map<String, Object> result = executor.execute(
                "scaleWorkload",
                Map.of("namespace", "prod", "target", "payment-api", "replicas", 3, "operationId", "op-1"));

        assertEquals(503, result.get("httpStatus"));
        assertEquals("MUTATION_ADAPTER_NOT_CONFIGURED", result.get("errorType"));
        verifyNoInteractions(httpClient);
    }

    @Test
    void shouldRouteMutationsToTheIndependentEndpointAndCredential() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getKubernetes().setEndpoint("http://readonly/api/tools/kubernetes");
        properties.getIntegrations().getKubernetes().setMutationEndpoint("http://mutation/api/tools/kubernetes");
        properties.getIntegrations().getKubernetes().setMutationBearerToken("mutation-secret");
        when(httpClient.post(eq("http://mutation/api/tools/kubernetes"), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of("status", "success", "httpStatus", 200, "response", Map.of("accepted", true)));
        KubernetesToolExecutor executor = new KubernetesToolExecutor(httpClient, properties);

        executor.execute(
                "scaleWorkload",
                Map.of("namespace", "prod", "target", "payment-api", "replicas", 3, "operationId", "op-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .post(
                        eq("http://mutation/api/tools/kubernetes"),
                        anyMap(),
                        anyInt(),
                        headers.capture(),
                        metadata.capture());
        assertEquals("Bearer mutation-secret", headers.getValue().get("Authorization"));
        assertEquals("kubernetes-mutation", metadata.getValue().get("targetSystem"));
        assertFalse(metadata.getValue().toString().contains("mutation-secret"));
    }

    @Test
    void shouldOpenTheMutationCircuitForDependencyFailuresWithoutRetryingTheAdapter() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDependencyCircuitBreaker().setFailureThreshold(1);
        properties.getIntegrations().getKubernetes().setMutationEndpoint("http://mutation/api/tools/kubernetes");
        when(httpClient.post(eq("http://mutation/api/tools/kubernetes"), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status",
                        "failed",
                        "httpStatus",
                        503,
                        "errorType",
                        "HttpStatusError",
                        "errorMessage",
                        "Tool endpoint returned HTTP 503"));
        DependencyCircuitBreaker circuitBreaker =
                new DependencyCircuitBreaker(properties, mock(KubeOnCallMetricsService.class));
        KubernetesToolExecutor executor = new KubernetesToolExecutor(httpClient, properties, circuitBreaker);

        Map<String, Object> first = executor.execute(
                "scaleWorkload",
                Map.of("namespace", "prod", "target", "payment-api", "replicas", 3, "operationId", "op-1"));
        Map<String, Object> rejected = executor.execute(
                "scaleWorkload",
                Map.of("namespace", "prod", "target", "payment-api", "replicas", 3, "operationId", "op-2"));

        assertEquals(503, first.get("httpStatus"));
        assertEquals("DEPENDENCY_CIRCUIT_OPEN", rejected.get("errorType"));
        assertEquals(
                "OPEN", circuitBreaker.snapshot().get("kubernetes-mutation").state());
        verify(httpClient, times(1))
                .post(eq("http://mutation/api/tools/kubernetes"), anyMap(), anyInt(), anyMap(), anyMap());
    }

    @Test
    void shouldNotOpenTheReadCircuitForCallerErrors() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDependencyCircuitBreaker().setFailureThreshold(1);
        properties.getIntegrations().getKubernetes().setEndpoint("http://readonly/api/tools/kubernetes");
        when(httpClient.post(eq("http://readonly/api/tools/kubernetes"), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status",
                        "failed",
                        "httpStatus",
                        400,
                        "errorType",
                        "HttpStatusError",
                        "errorMessage",
                        "Tool endpoint returned HTTP 400"));
        DependencyCircuitBreaker circuitBreaker =
                new DependencyCircuitBreaker(properties, mock(KubeOnCallMetricsService.class));
        KubernetesToolExecutor executor = new KubernetesToolExecutor(httpClient, properties, circuitBreaker);

        executor.execute("queryEvents", Map.of("namespace", "prod"));
        executor.execute("queryEvents", Map.of("namespace", "prod"));

        assertEquals("CLOSED", circuitBreaker.snapshot().get("kubernetes").state());
        verify(httpClient, times(2))
                .post(eq("http://readonly/api/tools/kubernetes"), anyMap(), anyInt(), anyMap(), anyMap());
    }

    @Test
    void shouldRejectUnregisteredActionsInsteadOfRoutingThemToTheReadOnlyAdapter() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getKubernetes().setEndpoint("http://readonly/api/tools/kubernetes");
        KubernetesToolExecutor executor = new KubernetesToolExecutor(httpClient, properties);

        Map<String, Object> result = executor.execute("deleteNamespace", Map.of("namespace", "prod"));

        assertEquals(400, result.get("httpStatus"));
        assertEquals("UNSUPPORTED_KUBERNETES_ACTION", result.get("errorType"));
        verifyNoInteractions(httpClient);
    }
}
