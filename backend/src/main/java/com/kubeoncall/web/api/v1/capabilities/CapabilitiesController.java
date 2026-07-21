package com.kubeoncall.web.api.v1.capabilities;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;

/**
 * {@code /api/v1/capabilities} — declares deployment-level features, limits and auth modes so the
 * same frontend build adapts to different environments. Capabilities describe what the deployment
 * supports, never what the current user may do; permission checks remain the backend's authority.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilitiesController {

    private final CapabilitiesService capabilitiesService;

    public CapabilitiesController(CapabilitiesService capabilitiesService) {
        this.capabilitiesService = capabilitiesService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> capabilities() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("release", capabilitiesService.release());
        data.put("features", capabilitiesService.features());
        data.put("limits", capabilitiesService.limits());
        data.put("auth", capabilitiesService.auth());
        data.put("links", capabilitiesService.links());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }
}
