package com.kubeoncall.web.api.v1.alarms;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.readmodel.AlarmQueryService;
import com.kubeoncall.alarm.readmodel.AlarmTimelineItem;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/alarms} — read model for the alarm console. List, detail and timeline all read
 * from MySQL (populated by the shadow-write projection); when MySQL is not enabled the endpoints
 * return {@code 503 SERVICE_UNAVAILABLE} rather than empty pages, so the frontend never mistakes a
 * missing deployment for "no alarms". Requires {@code alarm:read}.
 */
@RestController
@RequestMapping("/api/v1/alarms")
public class AlarmsController {

    private final AlarmQueryService queryService;
    private final V1Security security;

    public AlarmsController(AlarmQueryService queryService, V1Security security) {
        this.queryService = queryService;
        this.security = security;
    }

    @GetMapping
    public PageMeta.ListEnvelope<AlarmQueryService.AlarmListItem> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "status", required = false) String statuses,
            @RequestParam(name = "severity", required = false) String severities,
            @RequestParam(name = "cluster", required = false) String cluster,
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "q", required = false) String q) {
        security.requirePermission(PermissionCode.ALARM_READ);
        ensureAvailable();
        AlarmQueryService.AlarmListRequest request = new AlarmQueryService.AlarmListRequest(
                page, size, sort, statuses, severities, cluster, namespace, service, q);
        AlarmQueryService.AlarmListResult result = queryService.list(request);
        if (result.total() == 0 && result.page() == 0) {
            // service reported unavailable
            throw unavailable();
        }
        return PageMeta.ListEnvelope.of(
                result.items(), result.page(), result.size(), result.total(), RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{alarmId}")
    public ApiResponse<AlarmQueryService.AlarmDetail> detail(@PathVariable String alarmId) {
        security.requirePermission(PermissionCode.ALARM_READ);
        ensureAvailable();
        return queryService
                .detail(alarmId)
                .map(d -> ApiResponse.ok(d, RequestIdFilter.currentRequestId()))
                .orElseThrow(() -> notFound(alarmId));
    }

    @GetMapping("/{alarmId}/timeline")
    public ApiResponse<List<AlarmTimelineItem>> timeline(
            @PathVariable String alarmId,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "after", required = false) Instant after) {
        security.requirePermission(PermissionCode.ALARM_READ);
        ensureAvailable();
        List<AlarmTimelineItem> items = queryService.timeline(alarmId, limit, after);
        return ApiResponse.ok(items, RequestIdFilter.currentRequestId());
    }

    private void ensureAvailable() {
        if (!queryService.isAvailable()) {
            throw unavailable();
        }
    }

    private static V1ApiException unavailable() {
        return new V1ApiException(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                V1ApiErrorCode.SERVICE_UNAVAILABLE,
                "Alarm read model is not available");
    }

    private static V1ApiException notFound(String alarmId) {
        return new V1ApiException(
                HttpStatus.NOT_FOUND.value(), V1ApiErrorCode.NOT_FOUND, "Alarm not found: " + alarmId);
    }
}
