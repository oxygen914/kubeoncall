package com.kubeoncall.web.api.v1.overview;

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
        OverviewQueryService.Overview overview = queryService.build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeAlarms", overview.activeAlarms());
        data.put("pendingApprovals", overview.pendingApprovals());
        data.put("runningExecutions", overview.runningExecutions());
        data.put("failedExecutions", overview.failedExecutions());
        data.put("severityCounts", overview.severityCounts());
        data.put("statusCounts", overview.statusCounts());
        data.put("window", window == null ? "24h" : window);
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }
}
