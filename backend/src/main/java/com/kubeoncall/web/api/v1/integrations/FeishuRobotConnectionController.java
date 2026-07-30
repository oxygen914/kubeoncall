package com.kubeoncall.web.api.v1.integrations;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.notification.application.FeishuRobotConnectionService;
import com.kubeoncall.notification.application.FeishuRobotConnectionService.ConnectionException;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ID-only Feishu robot connection surface. The robot ID resolves server-managed credentials and
 * never accepts a webhook URL or secret from the request.
 */
@RestController
@RequestMapping("/api/v1/integrations/feishu/robots")
public class FeishuRobotConnectionController {

    private final ObjectProvider<FeishuRobotConnectionService> connectionServiceProvider;
    private final V1Security security;

    public FeishuRobotConnectionController(
            ObjectProvider<FeishuRobotConnectionService> connectionServiceProvider, V1Security security) {
        this.connectionServiceProvider = connectionServiceProvider;
        this.security = security;
    }

    @PostMapping("/{robotId}/connections")
    @Operation(
            operationId = "connectFeishuRobot",
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "202",
                            description = "Feishu robot connection verification queued",
                            useReturnTypeSchema = true))
    public ResponseEntity<ApiResponse<ConnectionView>> connect(
            @Parameter(
                            description = "Server-managed Feishu robot alias",
                            example = "infra-primary",
                            schema =
                                    @Schema(minLength = 1, maxLength = 64, pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                    @PathVariable
                    String robotId,
            @Parameter(
                            description = "Optional idempotency key for connection verification",
                            schema = @Schema(minLength = 16, maxLength = 128))
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        V1Principal principal = security.requirePermission(PermissionCode.INTEGRATION_MANAGE);
        FeishuRobotConnectionService service = connectionServiceProvider.getIfAvailable();
        if (service == null) {
            throw unavailable("Durable Feishu notification delivery is unavailable");
        }
        try {
            FeishuRobotConnectionService.ConnectionResult result =
                    service.connect(new FeishuRobotConnectionService.ConnectionCommand(
                            robotId,
                            idempotencyKey,
                            principal.user().id(),
                            principal.user().displayName(),
                            RequestIdFilter.currentRequestId(),
                            clientIp(request),
                            request.getHeader(HttpHeaders.USER_AGENT)));
            return ResponseEntity.accepted()
                    .body(ApiResponse.ok(
                            new ConnectionView(
                                    result.robotId(),
                                    result.eventId(),
                                    result.status().name(),
                                    result.deliveryIds()),
                            RequestIdFilter.currentRequestId()));
        } catch (ConnectionException exception) {
            if (exception.code() == FeishuRobotConnectionService.Code.NOT_FOUND) {
                throw V1ApiException.notFound(exception.getMessage());
            }
            throw invalid(exception.getMessage());
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

    public record ConnectionView(String robotId, String eventId, String status, List<String> deliveryIds) {}
}
