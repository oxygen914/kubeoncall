package com.kubeoncall.web;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.correlation.ChangeCorrelation;
import com.kubeoncall.alarm.correlation.ChangeCorrelationService;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.correlation.ChangeEventWebhookAuthenticator;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.web.dto.AlarmRequest;

/** Records deployment changes and exposes deterministic correlation for troubleshooting and replay. */
@LegacyApiController
@RestController
@RequestMapping("/api/change-events")
public class ChangeEventController {

    private final ChangeCorrelationService correlationService;
    private final AlarmNormalizer alarmNormalizer;
    private final KubeOnCallProperties properties;
    private final ChangeEventWebhookAuthenticator authenticator;

    public ChangeEventController(
            ChangeCorrelationService correlationService,
            AlarmNormalizer alarmNormalizer,
            KubeOnCallProperties properties,
            ChangeEventWebhookAuthenticator authenticator) {
        this.correlationService = correlationService;
        this.alarmNormalizer = alarmNormalizer;
        this.properties = properties;
        this.authenticator = authenticator;
    }

    @PostMapping
    public ChangeEvent record(
            @RequestHeader(value = org.springframework.http.HttpHeaders.AUTHORIZATION, required = false)
                    String authorization,
            @RequestBody ChangeEvent event) {
        requireEnabledAndAuthenticated(authorization);
        correlationService.record(event);
        return event;
    }

    @PostMapping("/correlations")
    public List<ChangeCorrelation> correlate(
            @RequestHeader(value = org.springframework.http.HttpHeaders.AUTHORIZATION, required = false)
                    String authorization,
            @RequestBody AlarmRequest alarm) {
        requireEnabledAndAuthenticated(authorization);
        return correlationService.findRelatedChanges(alarmNormalizer.normalize(alarm));
    }

    private void requireEnabledAndAuthenticated(String authorization) {
        if (!properties.getChangeEvents().isWebhookEnabled()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Change-event API is disabled");
        }
        authenticator.authenticateBearer(authorization);
    }
}
