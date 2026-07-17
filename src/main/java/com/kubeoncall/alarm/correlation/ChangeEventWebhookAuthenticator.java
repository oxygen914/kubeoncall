package com.kubeoncall.alarm.correlation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.integration.alertmanager.WebhookAuthenticationException;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Component
public class ChangeEventWebhookAuthenticator {

    private final KubeOnCallProperties properties;

    public ChangeEventWebhookAuthenticator(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public void authenticate(
            String provider, String authorization, Map<String, String> providerHeaders, byte[] rawPayload) {
        String normalized = provider == null ? "generic" : provider.toLowerCase(Locale.ROOT);
        Map<String, String> headers = providerHeaders == null ? Map.of() : providerHeaders;
        if ("github".equals(normalized)
                && configured(properties.getChangeEvents().getGithubWebhookSecret())) {
            authenticateGithub(headers.get("github-signature"), rawPayload);
            return;
        }
        if ("gitlab".equals(normalized)
                && configured(properties.getChangeEvents().getGitlabWebhookToken())) {
            compare(
                    properties.getChangeEvents().getGitlabWebhookToken(),
                    headers.get("gitlab-token"),
                    "GitLab webhook authentication failed");
            return;
        }
        if ("jenkins".equals(normalized)
                && configured(properties.getChangeEvents().getJenkinsWebhookToken())) {
            compare(
                    properties.getChangeEvents().getJenkinsWebhookToken(),
                    headers.get("jenkins-token"),
                    "Jenkins webhook authentication failed");
            return;
        }
        if ("argocd".equals(normalized)
                && configured(properties.getChangeEvents().getArgocdWebhookToken())) {
            compare(
                    properties.getChangeEvents().getArgocdWebhookToken(),
                    headers.get("argocd-token"),
                    "Argo CD webhook authentication failed");
            return;
        }
        authenticateBearer(authorization);
    }

    public void authenticateBearer(String authorization) {
        String mode = properties.getChangeEvents().getWebhookAuthMode();
        if (mode == null || mode.isBlank() || "none".equalsIgnoreCase(mode)) {
            return;
        }
        if (!"bearer".equalsIgnoreCase(mode)) {
            throw new WebhookAuthenticationException("Unsupported change-event webhook auth mode");
        }
        String expected = properties.getChangeEvents().getWebhookToken();
        if (expected == null || expected.isBlank()) {
            throw new WebhookAuthenticationException("Change-event webhook credential is not configured");
        }
        String prefix = "Bearer ";
        if (authorization == null || !authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new WebhookAuthenticationException("Change-event webhook authentication failed");
        }
        String supplied = authorization.substring(prefix.length()).trim();
        compare(expected, supplied, "Change-event webhook authentication failed");
    }

    private void authenticateGithub(String signature, byte[] rawPayload) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new WebhookAuthenticationException("GitHub webhook authentication failed");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getChangeEvents().getGithubWebhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            String expected =
                    "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawPayload == null ? new byte[0] : rawPayload));
            compare(expected, signature, "GitHub webhook authentication failed");
        } catch (WebhookAuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WebhookAuthenticationException("GitHub webhook authentication is unavailable", ex);
        }
    }

    private void compare(String expected, String supplied, String message) {
        if (expected == null
                || supplied == null
                || !MessageDigest.isEqual(
                        expected.trim().getBytes(StandardCharsets.UTF_8),
                        supplied.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookAuthenticationException(message);
        }
    }

    private boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
