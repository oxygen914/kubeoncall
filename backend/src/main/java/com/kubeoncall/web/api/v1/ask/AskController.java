package com.kubeoncall.web.api.v1.ask;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.service.AskService;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/ask} — runtime Q&A over the v1 surface. Delegates to the existing {@link AskService}
 * workflow but wraps the response in the unified {@code {data, meta}} envelope and requires
 * {@code ask:execute}. The session id is optional; when absent the service starts a new session.
 */
@RestController("v1AskController")
@RequestMapping("/api/v1/ask")
public class AskController {

    private final AskService askService;
    private final V1Security security;

    public AskController(AskService askService, V1Security security) {
        this.askService = askService;
        this.security = security;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> ask(@Valid @RequestBody AskRequest request) {
        security.requirePermission(PermissionCode.ASK_EXECUTE);
        AskService.AskExecutionResult result =
                askService.handleReadOnlyCompatibility(request.question(), request.sessionId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", result.executionId());
        data.put("status", result.status());
        data.put("message", result.message());
        data.put("sessionId", result.sessionId());
        data.put("details", result.details());
        data.put("deprecated", true);
        data.put("replacement", "/api/v1/executions");
        data.put("executionMode", "READ_ONLY_COMPATIBILITY");
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    public record AskRequest(@NotBlank @Size(max = 4000) String question, String sessionId) {}
}
