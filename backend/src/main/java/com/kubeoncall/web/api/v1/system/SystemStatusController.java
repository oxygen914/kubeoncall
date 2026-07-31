package com.kubeoncall.web.api.v1.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.web.api.v1.ApiResponse;

/**
 * {@code /api/v1/system/status} — lightweight liveness/style status for the new console. Mirrors the
 * legacy {@code /api/status} contract but under the versioned surface and wrapped in the unified
 * {@code {data, meta}} envelope.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    @GetMapping("/status")
    public ApiResponse<SystemStatus> status() {
        return ApiResponse.ok(new SystemStatus("KubeOnCall", "UP"), requestId());
    }

    private static String requestId() {
        return com.kubeoncall.web.api.v1.RequestIdFilter.currentRequestId();
    }

    public record SystemStatus(String service, String status) {}
}
