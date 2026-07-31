package com.kubeoncall.alarm.integration.alertmanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

/** Validates bearer credentials without logging the supplied or configured secret. */
@Service
public class AlertmanagerWebhookAuthenticator {

    private final KubeOnCallProperties properties;

    public AlertmanagerWebhookAuthenticator(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public void authenticate(String authorization) {
        String mode = properties.getAlarm().getAlertmanagerWebhookAuthMode();
        if (mode == null || mode.isBlank() || "none".equalsIgnoreCase(mode)) {
            return;
        }
        if (!"bearer".equalsIgnoreCase(mode)) {
            throw new WebhookAuthenticationException("Unsupported webhook auth mode");
        }
        String expected = configuredToken();
        if (expected == null || expected.isBlank()) {
            throw new WebhookAuthenticationException("Webhook credential is not configured");
        }
        String prefix = "Bearer ";
        if (authorization == null || !authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new WebhookAuthenticationException("Webhook authentication failed");
        }
        String actual = authorization.substring(prefix.length()).trim();
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookAuthenticationException("Webhook authentication failed");
        }
    }

    private String configuredToken() {
        String token = properties.getAlarm().getAlertmanagerWebhookToken();
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        String tokenFile = properties.getAlarm().getAlertmanagerWebhookTokenFile();
        if (tokenFile == null || tokenFile.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            throw new WebhookAuthenticationException("Webhook credential is unavailable", ex);
        }
    }
}
