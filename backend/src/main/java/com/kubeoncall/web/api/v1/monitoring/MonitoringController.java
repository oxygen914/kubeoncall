package com.kubeoncall.web.api.v1.monitoring;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.monitoring.MonitoringDataSourceException;
import com.kubeoncall.monitoring.MonitoringOperationsService;
import com.kubeoncall.monitoring.MonitoringQueryService;
import com.kubeoncall.monitoring.MonitoringQueryService.CpuWindow;
import com.kubeoncall.monitoring.MonitoringQueryService.HealthWindow;
import com.kubeoncall.monitoring.MonitoringViews.AdviceFeed;
import com.kubeoncall.monitoring.MonitoringViews.ClusterList;
import com.kubeoncall.monitoring.MonitoringViews.CorrelationFeed;
import com.kubeoncall.monitoring.MonitoringViews.CpuTrend;
import com.kubeoncall.monitoring.MonitoringViews.HealthTrend;
import com.kubeoncall.monitoring.MonitoringViews.NodeList;
import com.kubeoncall.monitoring.MonitoringViews.PodList;
import com.kubeoncall.monitoring.MonitoringViews.Scope;
import com.kubeoncall.monitoring.MonitoringViews.ScopeCatalog;
import com.kubeoncall.monitoring.MonitoringViews.Summary;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private static final Pattern RESOURCE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,252}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9](?:[-a-z0-9.]{0,251}[a-z0-9])?");

    private final MonitoringQueryService queryService;
    private final MonitoringOperationsService operationsService;
    private final V1Security security;

    public MonitoringController(
            MonitoringQueryService queryService, MonitoringOperationsService operationsService, V1Security security) {
        this.queryService = queryService;
        this.operationsService = operationsService;
        this.security = security;
    }

    @GetMapping("/clusters")
    public ApiResponse<ClusterList> clusters() {
        return read(queryService::clusters);
    }

    @GetMapping("/scopes")
    public ApiResponse<ScopeCatalog> scopes(
            @RequestParam(required = false) String cluster, @RequestParam(required = false) String environment) {
        String checkedCluster = optionalResourceName(cluster, "cluster");
        String checkedEnvironment = optionalResourceName(environment, "environment");
        return read(() -> queryService.scopes(checkedCluster, checkedEnvironment));
    }

    @GetMapping("/summary")
    public ApiResponse<Summary> summary(
            @RequestParam String cluster,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String namespace) {
        return read(() -> queryService.summary(
                resourceName(cluster, "cluster"),
                optionalResourceName(environment, "environment"),
                optionalNamespace(namespace)));
    }

    @Operation(operationId = "monitoringNodes")
    @GetMapping("/nodes")
    public ApiResponse<NodeList> nodes(
            @RequestParam String cluster, @RequestParam(required = false) String environment) {
        return read(() ->
                queryService.nodes(resourceName(cluster, "cluster"), optionalResourceName(environment, "environment")));
    }

    @GetMapping("/pods")
    public ApiResponse<PodList> pods(
            @RequestParam String cluster,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String phase,
            @RequestParam(defaultValue = "100") int limit) {
        String checkedNamespace = optionalNamespace(namespace);
        String checkedPhase = phase == null || phase.isBlank() ? null : phase(phase);
        if (limit < 1 || limit > 500) {
            throw invalid("limit must be between 1 and 500");
        }
        return read(() -> queryService.pods(
                resourceName(cluster, "cluster"),
                optionalResourceName(environment, "environment"),
                checkedNamespace,
                checkedPhase,
                limit));
    }

    @GetMapping("/nodes/{node}/cpu")
    public ApiResponse<CpuTrend> cpuTrend(
            @PathVariable String node,
            @RequestParam String cluster,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "15m") String window) {
        CpuWindow selected;
        try {
            selected = CpuWindow.parse(window);
        } catch (IllegalArgumentException ex) {
            throw invalid(ex.getMessage());
        }
        return read(() -> queryService.cpuTrend(
                resourceName(cluster, "cluster"),
                optionalResourceName(environment, "environment"),
                resourceName(node, "node"),
                selected));
    }

    @GetMapping("/health/trend")
    public ApiResponse<HealthTrend> healthTrend(
            @RequestParam String cluster,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "6h") String window) {
        HealthWindow selected;
        try {
            selected = HealthWindow.parse(window);
        } catch (IllegalArgumentException ex) {
            throw invalid(ex.getMessage());
        }
        return read(() -> queryService.healthTrend(
                resourceName(cluster, "cluster"),
                optionalResourceName(environment, "environment"),
                optionalNamespace(namespace),
                selected));
    }

    @Operation(operationId = "monitoringCorrelations")
    @GetMapping("/correlations")
    public ApiResponse<CorrelationFeed> correlations(
            @RequestParam String cluster,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "6h") String window,
            @RequestParam(defaultValue = "10") int limit) {
        security.requirePermission(PermissionCode.DASHBOARD_READ);
        security.requirePermission(PermissionCode.ALARM_READ);
        security.requirePermission(PermissionCode.CHANGE_READ);
        if (limit < 1 || limit > 20) {
            throw invalid("limit must be between 1 and 20");
        }
        Duration correlationWindow = historyDuration(window);
        Scope scope = scope(cluster, environment, namespace);
        return readAuthorized(() -> operationsService.correlations(scope, correlationWindow, limit));
    }

    @GetMapping("/advice")
    public ApiResponse<AdviceFeed> advice(
            @RequestParam String cluster,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "6h") String window) {
        security.requirePermission(PermissionCode.DASHBOARD_READ);
        Scope scope = scope(cluster, environment, namespace);
        boolean includeCorrelations =
                security.hasPermission(PermissionCode.ALARM_READ) && security.hasPermission(PermissionCode.CHANGE_READ);
        return readAuthorized(() -> operationsService.advice(scope, historyDuration(window), includeCorrelations));
    }

    private <T> ApiResponse<T> read(Supplier<T> query) {
        security.requirePermission(PermissionCode.DASHBOARD_READ);
        return readAuthorized(query);
    }

    private <T> ApiResponse<T> readAuthorized(Supplier<T> query) {
        try {
            return ApiResponse.ok(query.get(), RequestIdFilter.currentRequestId());
        } catch (MonitoringDataSourceException ex) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Monitoring data source is unavailable");
        }
    }

    private static String resourceName(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!RESOURCE_NAME.matcher(normalized).matches()) {
            throw invalid(field + " has an invalid format");
        }
        return normalized;
    }

    private static String optionalResourceName(String value, String field) {
        return value == null || value.isBlank() ? null : resourceName(value, field);
    }

    private static String optionalNamespace(String value) {
        return value == null || value.isBlank() ? null : namespace(value);
    }

    private static Scope scope(String cluster, String environment, String namespace) {
        return new Scope(
                resourceName(cluster, "cluster"),
                optionalResourceName(environment, "environment"),
                optionalNamespace(namespace));
    }

    private static Duration historyDuration(String value) {
        try {
            return HealthWindow.parse(value).duration();
        } catch (IllegalArgumentException ex) {
            throw invalid(ex.getMessage());
        }
    }

    private static String namespace(String value) {
        String normalized = value.trim().toLowerCase();
        if (!NAMESPACE.matcher(normalized).matches()) {
            throw invalid("namespace has an invalid format");
        }
        return normalized;
    }

    private static String phase(String value) {
        String normalized = value.trim();
        if (!Pattern.matches("(?i)Pending|Running|Succeeded|Failed|Unknown", normalized)) {
            throw invalid("phase must be one of Pending, Running, Succeeded, Failed, Unknown");
        }
        return normalized;
    }

    private static V1ApiException invalid(String message) {
        return new V1ApiException(HttpStatus.BAD_REQUEST.value(), V1ApiErrorCode.INVALID_REQUEST, message);
    }
}
