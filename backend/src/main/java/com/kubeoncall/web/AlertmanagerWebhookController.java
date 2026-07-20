package com.kubeoncall.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.ingest.AlarmIngestionService;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerPayloadValidator;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerWebhookAuthenticator;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerWebhookRequest;
import com.kubeoncall.alarm.integration.alertmanager.AlertmanagerWebhookResponse;
import com.kubeoncall.common.config.KubeOnCallProperties;

/** HTTP boundary for Alertmanager. A 202 is emitted only after durable inbox acceptance. */
@RestController
@RequestMapping("/api/integrations/alertmanager")
public class AlertmanagerWebhookController {

    private final KubeOnCallProperties properties;
    private final AlertmanagerWebhookAuthenticator authenticator;
    private final AlertmanagerPayloadValidator payloadValidator;
    private final AlarmIngestionService ingestionService;

    public AlertmanagerWebhookController(
            KubeOnCallProperties properties,
            AlertmanagerWebhookAuthenticator authenticator,
            AlertmanagerPayloadValidator payloadValidator,
            AlarmIngestionService ingestionService) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.payloadValidator = payloadValidator;
        this.ingestionService = ingestionService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<AlertmanagerWebhookResponse> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false, defaultValue = "-1")
                    long contentLength,
            @RequestBody AlertmanagerWebhookRequest request) {
        if (!properties.getAlarm().isAlertmanagerWebhookEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        authenticator.authenticate(authorization);
        payloadValidator.validate(request, contentLength);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestionService.acceptAlertmanager(request));
    }
}
