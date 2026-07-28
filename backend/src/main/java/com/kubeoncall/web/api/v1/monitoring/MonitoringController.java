package com.kubeoncall.web.api.v1.monitoring;

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
import com.kubeoncall.monitoring.MonitoringQueryService;
import com.kubeoncall.monitoring.MonitoringQueryService.CpuWindow;
import com.kubeoncall.monitoring.MonitoringViews.ClusterList;
import com.kubeoncall.monitoring.MonitoringViews.CpuTrend;
import com.kubeoncall.monitoring.MonitoringViews.NodeList;
import com.kubeoncall.monitoring.MonitoringViews.PodList;
import com.kubeoncall.monitoring.MonitoringViews.Summary;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private static final Pattern RESOURCE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,252}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9](?:[-a-z0-9.]{0,251}[a-z0-9])?");

    private final MonitoringQueryService queryService;
    private final V1Security security;

    public MonitoringController(MonitoringQueryService queryService, V1Security security) {
        this.queryService = queryService;
        this.security = security;
    }

    @GetMapping("/clusters")
    public ApiResponse<ClusterList> clusters() {
        return read(queryService::clusters);
    }

    @GetMapping("/summary")
    public ApiResponse<Summary> summary(@RequestParam String cluster) {
        return read(() -> queryService.summary(resourceName(cluster, "cluster")));
    }

    @GetMapping("/nodes")
    public ApiResponse<NodeList> nodes(@RequestParam String cluster) {
        return read(() -> queryService.nodes(resourceName(cluster, "cluster")));
    }

    @GetMapping("/pods")
    public ApiResponse<PodList> pods(
            @RequestParam String cluster,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String phase,
            @RequestParam(defaultValue = "100") int limit) {
        String checkedNamespace = namespace == null || namespace.isBlank() ? null : namespace(namespace);
        String checkedPhase = phase == null || phase.isBlank() ? null : phase(phase);
        if (limit < 1 || limit > 500) {
            throw invalid("limit must be between 1 and 500");
        }
        return read(() -> queryService.pods(resourceName(cluster, "cluster"), checkedNamespace, checkedPhase, limit));
    }

    @GetMapping("/nodes/{node}/cpu")
    public ApiResponse<CpuTrend> cpuTrend(
            @PathVariable String node,
            @RequestParam String cluster,
            @RequestParam(defaultValue = "15m") String window) {
        CpuWindow selected;
        try {
            selected = CpuWindow.parse(window);
        } catch (IllegalArgumentException ex) {
            throw invalid(ex.getMessage());
        }
        return read(
                () -> queryService.cpuTrend(resourceName(cluster, "cluster"), resourceName(node, "node"), selected));
    }

    private <T> ApiResponse<T> read(Supplier<T> query) {
        security.requirePermission(PermissionCode.DASHBOARD_READ);
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
