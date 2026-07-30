package com.kubeoncall.web.api.v1.integrations;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.notification.application.NotificationReplayService;
import com.kubeoncall.notification.application.NotificationReplayService.ReplayException;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

import io.swagger.v3.oas.annotations.Operation;

/** Privileged, audited replay endpoint for terminal notification failures. */
@RestController
@RequestMapping("/api/v1/integrations/notifications")
public class NotificationReplayController {

    private final ObjectProvider<NotificationReplayService> replayServiceProvider;
    private final V1Security security;

    public NotificationReplayController(
            ObjectProvider<NotificationReplayService> replayServiceProvider, V1Security security) {
        this.replayServiceProvider = replayServiceProvider;
        this.security = security;
    }

    @PostMapping("/{deliveryId}/replays")
    @Operation(
            operationId = "replayNotificationDelivery",
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "202",
                            description = "Notification replay accepted",
                            useReturnTypeSchema = true))
    public ResponseEntity<ApiResponse<ReplayView>> replay(
            @PathVariable String deliveryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true) @Valid @RequestBody(required = false)
                    ReplayRequest body,
            HttpServletRequest request) {
        V1Principal principal = security.requirePermission(PermissionCode.INTEGRATION_MANAGE);
        if (body == null) {
            throw invalid("Request body is required");
        }
        NotificationReplayService service = replayServiceProvider.getIfAvailable();
        if (service == null) {
            throw unavailable("Durable notification delivery is unavailable");
        }
        try {
            NotificationReplayService.ReplayResult result = service.replay(new NotificationReplayService.ReplayCommand(
                    deliveryId,
                    principal.user().id(),
                    principal.user().displayName(),
                    body.reason(),
                    RequestIdFilter.currentRequestId(),
                    clientIp(request),
                    request.getHeader(HttpHeaders.USER_AGENT)));
            return ResponseEntity.accepted()
                    .body(ApiResponse.ok(
                            new ReplayView(result.deliveryId(), result.status().name(), result.replayCount()),
                            RequestIdFilter.currentRequestId()));
        } catch (ReplayException exception) {
            if (exception.code() == NotificationReplayService.Code.NOT_FOUND) {
                throw V1ApiException.notFound(exception.getMessage());
            }
            throw V1ApiException.conflict(V1ApiErrorCode.CONFLICT, exception.getMessage());
        }
    }

    private static V1ApiException invalid(String message) {
        return new V1ApiException(HttpStatus.BAD_REQUEST.value(), V1ApiErrorCode.INVALID_REQUEST, message);
    }

    private static V1ApiException unavailable(String message) {
        return new V1ApiException(HttpStatus.SERVICE_UNAVAILABLE.value(), V1ApiErrorCode.SERVICE_UNAVAILABLE, message);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    public record ReplayRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record ReplayView(String deliveryId, String status, int replayCount) {}
}
