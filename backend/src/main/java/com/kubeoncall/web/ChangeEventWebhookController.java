package com.kubeoncall.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.correlation.ChangeCorrelationService;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.correlation.ChangeEventWebhookAuthenticator;
import com.kubeoncall.alarm.correlation.CiCdChangeEventMapper;
import com.kubeoncall.alarm.integration.alertmanager.WebhookAuthenticationException;
import com.kubeoncall.alarm.integration.alertmanager.WebhookPayloadTooLargeException;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;

@RestController
@RequestMapping("/api/integrations/change-events")
public class ChangeEventWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventWebhookController.class);

    private final KubeOnCallProperties properties;
    private final ChangeEventWebhookAuthenticator authenticator;
    private final CiCdChangeEventMapper mapper;
    private final ChangeCorrelationService correlationService;
    private final KubeOnCallMetricsService metricsService;
    private final ExecutionAuditService auditService;
    private final ObjectMapper objectMapper;

    public ChangeEventWebhookController(
            KubeOnCallProperties properties,
            ChangeEventWebhookAuthenticator authenticator,
            CiCdChangeEventMapper mapper,
            ChangeCorrelationService correlationService,
            KubeOnCallMetricsService metricsService,
            ExecutionAuditService auditService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.mapper = mapper;
        this.correlationService = correlationService;
        this.metricsService = metricsService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<ChangeEventWebhookResponse> receive(
            @PathVariable String provider,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-KubeOnCall-Event", required = false) String genericEvent,
            @RequestHeader(value = "X-KubeOnCall-Delivery", required = false) String genericDelivery,
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String githubDelivery,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String githubSignature,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String gitlabEvent,
            @RequestHeader(value = "X-Gitlab-Event-UUID", required = false) String gitlabDelivery,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String gitlabToken,
            @RequestHeader(value = "X-ArgoCD-Event", required = false) String argocdEvent,
            @RequestHeader(value = "X-ArgoCD-Delivery", required = false) String argocdDelivery,
            @RequestHeader(value = "X-ArgoCD-Token", required = false) String argocdToken,
            @RequestHeader(value = "X-Jenkins-Event", required = false) String jenkinsEvent,
            @RequestHeader(value = "X-Jenkins-Event-Id", required = false) String jenkinsDelivery,
            @RequestHeader(value = "X-Jenkins-Token", required = false) String jenkinsToken,
            @RequestBody byte[] rawPayload) {
        Instant startedAt = Instant.now();
        String source = provider == null || provider.isBlank()
                ? "unknown"
                : provider.trim().toLowerCase();
        if (!properties.getChangeEvents().isWebhookEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            if (rawPayload.length > Math.max(1024, properties.getChangeEvents().getWebhookMaxPayloadBytes())) {
                throw new WebhookPayloadTooLargeException("Change-event webhook payload exceeds the configured limit");
            }
            authenticator.authenticate(
                    provider,
                    authorization,
                    Map.of(
                            "github-signature", safe(githubSignature),
                            "gitlab-token", safe(gitlabToken),
                            "jenkins-token", safe(jenkinsToken),
                            "argocd-token", safe(argocdToken)),
                    rawPayload);
            Map<String, Object> payload = parse(rawPayload);
            String eventType = first(genericEvent, githubEvent, gitlabEvent, argocdEvent, jenkinsEvent);
            String deliveryId = first(genericDelivery, githubDelivery, gitlabDelivery, argocdDelivery, jenkinsDelivery);
            ChangeEvent event = mapper.map(provider, eventType, deliveryId, payload);
            boolean accepted = correlationService.recordIfAbsent(event);
            String outcome = accepted ? "accepted" : "duplicate";
            metricsService.recordChangeEvent(outcome, event.changeSource());
            recordAudit(
                    outcome,
                    accepted ? "SUCCESS" : "DUPLICATE",
                    "Change event " + outcome,
                    startedAt,
                    metadata(event.changeSource(), eventType, deliveryId, event.changeId(), null));
            return ResponseEntity.status(accepted ? HttpStatus.ACCEPTED : HttpStatus.OK)
                    .body(new ChangeEventWebhookResponse(event.changeId(), accepted, !accepted, event.changeSource()));
        } catch (WebhookAuthenticationException ex) {
            recordFailure("authentication_rejected", source, startedAt, ex);
            throw ex;
        } catch (WebhookPayloadTooLargeException ex) {
            recordFailure("payload_too_large", source, startedAt, ex);
            throw ex;
        } catch (IllegalArgumentException ex) {
            recordFailure("invalid_payload", source, startedAt, ex);
            throw ex;
        } catch (RuntimeException ex) {
            recordFailure("storage_or_processing_failed", source, startedAt, ex);
            throw ex;
        }
    }

    private void recordFailure(String outcome, String source, Instant startedAt, RuntimeException exception) {
        metricsService.recordChangeEvent(outcome, source);
        recordAudit(
                outcome,
                "FAILED",
                "Change event rejected: " + outcome,
                startedAt,
                metadata(source, null, null, null, exception.getClass().getSimpleName()));
    }

    private void recordAudit(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        try {
            auditService.recordChangeEventOperation(operation, status, summary, startedAt, metadata);
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to persist ChangeEvent audit: operation={}, errorType={}",
                    operation,
                    ex.getClass().getSimpleName());
        }
    }

    private Map<String, Object> metadata(
            String source, String eventType, String deliveryId, String changeId, String errorType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", safe(source));
        putIfPresent(metadata, "eventType", eventType);
        putIfPresent(metadata, "deliveryId", deliveryId);
        putIfPresent(metadata, "changeId", changeId);
        putIfPresent(metadata, "errorType", errorType);
        return Map.copyOf(metadata);
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, Object> parse(byte[] payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Change-event webhook payload must be a JSON object", ex);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ChangeEventWebhookResponse(String changeId, boolean accepted, boolean duplicate, String source) {}
}
