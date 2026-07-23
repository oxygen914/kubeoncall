package com.kubeoncall.web.api.v1.overview;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/overview} — Dashboard aggregate (WBS-5 §4). Returns active-alarm, severity,
 * approval and execution counts in one response so the console loads the home page with a single
 * query. Requires {@code dashboard:read}; the aggregate never triggers external probes, so it is
 * safe to call frequently.
 */
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {

    private final OverviewQueryService queryService;
    private final V1Security security;

    public OverviewController(OverviewQueryService queryService, V1Security security) {
        this.queryService = queryService;
        this.security = security;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> overview(@RequestParam(name = "window", required = false) String window) {
        security.requirePermission(PermissionCode.DASHBOARD_READ);
        if (!queryService.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Overview read model is not available");
        }
        OverviewWindow selectedWindow = OverviewWindow.parse(window);
        Instant windowStart = Instant.now().minus(selectedWindow.duration());
        OverviewQueryService.Overview overview = queryService.build(windowStart);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeAlarms", overview.activeAlarms());
        data.put("pendingApprovals", overview.pendingApprovals());
        data.put("runningExecutions", overview.runningExecutions());
        data.put("failedExecutions", overview.failedExecutions());
        data.put("severityCounts", overview.severityCounts());
        data.put("statusCounts", overview.statusCounts());
        data.put("window", selectedWindow.value());
        data.put("windowStart", windowStart.toString());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private enum OverviewWindow {
        ONE_HOUR("1h", Duration.ofHours(1)),
        SIX_HOURS("6h", Duration.ofHours(6)),
        TWENTY_FOUR_HOURS("24h", Duration.ofHours(24)),
        SEVEN_DAYS("7d", Duration.ofDays(7)),
        THIRTY_DAYS("30d", Duration.ofDays(30));

        private final String value;
        private final Duration duration;

        OverviewWindow(String value, Duration duration) {
            this.value = value;
            this.duration = duration;
        }

        String value() {
            return value;
        }

        Duration duration() {
            return duration;
        }

        static OverviewWindow parse(String raw) {
            String value = raw == null || raw.isBlank() ? "24h" : raw.trim().toLowerCase();
            for (OverviewWindow candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "window must be one of 1h, 6h, 24h, 7d, 30d");
        }
    }
}
