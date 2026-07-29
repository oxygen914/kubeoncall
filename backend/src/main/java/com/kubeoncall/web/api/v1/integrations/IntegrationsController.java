package com.kubeoncall.web.api.v1.integrations;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.observability.DependencyCircuitBreaker;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1Security;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;
import com.kubeoncall.workflow.execution.WorkflowNodeExecutionRecord;

/**
 * Operator view of configured integrations, process-local circuit state and durable notification
 * node deliveries. Endpoint values are sanitized so credentials and query parameters are never
 * exposed to the browser.
 */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationsController {

    private final KubeOnCallProperties properties;
    private final DependencyCircuitBreaker circuitBreaker;
    private final ObjectProvider<WorkflowExecutionRepository> executionRepositoryProvider;
    private final V1Security security;

    public IntegrationsController(
            KubeOnCallProperties properties,
            DependencyCircuitBreaker circuitBreaker,
            ObjectProvider<WorkflowExecutionRepository> executionRepositoryProvider,
            V1Security security) {
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.executionRepositoryProvider = executionRepositoryProvider;
        this.security = security;
    }

    @GetMapping
    public ApiResponse<IntegrationCatalogView> catalog() {
        security.requirePermission(PermissionCode.INTEGRATION_READ);
        Map<String, DependencyCircuitBreaker.CircuitState> circuits = circuitBreaker.snapshot();
        List<IntegrationView> integrations = new ArrayList<>();
        integrations.add(view(
                "kubernetes", "Kubernetes API", properties.getIntegrations().getKubernetes(), circuits));
        integrations.add(
                view("prometheus", "Prometheus", properties.getIntegrations().getPrometheus(), circuits));
        integrations.add(view("loki", "Loki", properties.getIntegrations().getLoki(), circuits));
        integrations.add(view(
                "alertmanager", "Alertmanager", properties.getIntegrations().getAlertmanager(), circuits));
        integrations.add(view(
                "notification-webhook",
                "Notification Webhook",
                properties.getIntegrations().getNotification(),
                circuits));
        integrations.add(view(
                "incident", "Incident Platform", properties.getIntegrations().getIncident(), circuits));
        integrations.add(
                view("device", "Device Platform", properties.getIntegrations().getDevice(), circuits));
        integrations.add(
                view("database", "Database Tool", properties.getIntegrations().getDatabase(), circuits));
        integrations.add(internalView("redis", "Redis", circuits));
        integrations.add(internalView("elasticsearch", "Elasticsearch", circuits));
        integrations.add(internalView("minio", "MinIO", circuits));
        return ApiResponse.ok(new IntegrationCatalogView(List.copyOf(integrations), circuits), requestId());
    }

    @GetMapping("/notifications")
    public PageMeta.ListEnvelope<NotificationDeliveryView> notifications(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.INTEGRATION_READ);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        WorkflowExecutionRepository repository = executionRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return PageMeta.ListEnvelope.of(List.of(), normalizedPage, normalizedSize, 0, requestId());
        }
        WorkflowExecutionRepository.NodePage result =
                repository.listNodesByName("notificationNode", normalizedPage, normalizedSize);
        return PageMeta.ListEnvelope.of(
                result.rows().stream().map(IntegrationsController::deliveryView).toList(),
                normalizedPage,
                normalizedSize,
                result.total(),
                requestId());
    }

    private static IntegrationView view(
            String id,
            String name,
            KubeOnCallProperties.Endpoint endpoint,
            Map<String, DependencyCircuitBreaker.CircuitState> circuits) {
        String configuredEndpoint = endpoint.getEndpoint();
        boolean configured = configuredEndpoint != null && !configuredEndpoint.isBlank();
        DependencyCircuitBreaker.CircuitState circuit = circuits.get(id);
        return new IntegrationView(
                id,
                name,
                configured,
                configured ? sanitizeEndpoint(configuredEndpoint) : null,
                endpoint.getTimeoutMillis(),
                circuit == null ? "NOT_OBSERVED" : circuit.state(),
                circuit == null ? 0 : circuit.consecutiveFailures(),
                circuit == null ? null : circuit.openedAt());
    }

    private static IntegrationView internalView(
            String id, String name, Map<String, DependencyCircuitBreaker.CircuitState> circuits) {
        DependencyCircuitBreaker.CircuitState circuit = circuits.get(id);
        return new IntegrationView(
                id,
                name,
                true,
                null,
                null,
                circuit == null ? "NOT_OBSERVED" : circuit.state(),
                circuit == null ? 0 : circuit.consecutiveFailures(),
                circuit == null ? null : circuit.openedAt());
    }

    private static String sanitizeEndpoint(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            String authority = uri.getHost();
            if (authority == null || authority.isBlank()) {
                return "configured";
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            return uri.getScheme() + "://" + authority + port + path;
        } catch (IllegalArgumentException ex) {
            return "configured";
        }
    }

    private static NotificationDeliveryView deliveryView(WorkflowNodeExecutionRecord row) {
        return new NotificationDeliveryView(
                row.publicId(),
                row.executionPublicId(),
                row.status(),
                row.outputSummary(),
                row.errorCode(),
                row.errorSummary(),
                row.startedAt(),
                row.finishedAt(),
                row.durationMs(),
                row.attempt());
    }

    private static String requestId() {
        return RequestIdFilter.currentRequestId();
    }

    public record IntegrationCatalogView(
            List<IntegrationView> integrations, Map<String, DependencyCircuitBreaker.CircuitState> circuits) {}

    public record IntegrationView(
            String id,
            String name,
            boolean configured,
            String endpoint,
            Integer timeoutMillis,
            String circuitState,
            int consecutiveFailures,
            Instant circuitOpenedAt) {}

    public record NotificationDeliveryView(
            String id,
            String executionId,
            String status,
            String summary,
            String errorCode,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            int attempt) {}
}
