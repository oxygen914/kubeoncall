package com.kubeoncall.web.api.v1.clientevents;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kubeoncall.observability.SensitiveDataRedactor;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Accepts a strict, summary-only browser error envelope and emits it to the structured application
 * log. Request bodies, headers, cookies, stack traces and arbitrary metadata are not part of the
 * contract.
 */
@RestController
@RequestMapping("/api/v1/client-events")
public class ClientEventsController {

    private static final Logger log = LoggerFactory.getLogger(ClientEventsController.class);
    private static final SensitiveDataRedactor REDACTOR = SensitiveDataRedactor.STANDARD;

    private final V1Security security;
    private final ClientEventRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    public ClientEventsController(
            V1Security security, ClientEventRateLimiter rateLimiter, MeterRegistry meterRegistry) {
        this.security = security;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> report(@Valid @RequestBody ClientEventRequest event) {
        V1Principal principal = security.requireAuthenticated();
        String principalId = principal.user().publicId();
        boolean accepted = rateLimiter.tryAcquire(principalId, event.errorCode(), event.route());
        meterRegistry
                .counter("kubeoncall_client_events_total", "outcome", accepted ? "accepted" : "rate_limited")
                .increment();
        if (accepted) {
            log.warn(
                    "Client error event: release={}, environment={}, route={}, errorCode={}, "
                            + "linkedRequestId={}, browser={}, message={}",
                    safe(event.release(), 128),
                    safe(event.environment(), 64),
                    safe(event.route(), 256),
                    safe(event.errorCode(), 128),
                    safe(event.requestId(), 128),
                    safe(event.browser(), 120),
                    safe(event.message(), 240));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(Map.of("accepted", accepted), RequestIdFilter.currentRequestId()));
    }

    private static String safe(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String redacted =
                REDACTOR.redactText(value).replaceAll("[\\r\\n\\t]+", " ").trim();
        return redacted.length() <= maxLength ? redacted : redacted.substring(0, maxLength);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ClientEventRequest(
            @NotBlank @Size(max = 128) String release,
            @NotBlank @Size(max = 64) String environment,

            @NotBlank @Size(max = 256) @Pattern(regexp = "/[^?#\\r\\n]*") String route,

            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_.:-]+") String errorCode,

            @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_.:-]+") String requestId,

            @NotBlank @Size(max = 120) String browser,
            @NotBlank @Size(max = 240) String message) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object ignored) {
            throw new IllegalArgumentException("Unsupported client event field: " + name);
        }
    }
}
