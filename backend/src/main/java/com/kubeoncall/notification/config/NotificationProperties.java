package com.kubeoncall.notification.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kubeoncall.notification.domain.NotificationCapability;

/** Governed routes and secret-bearing provider targets for the notification subsystem. */
@ConfigurationProperties(prefix = "kubeoncall.notifications")
public class NotificationProperties {

    private boolean enabled;
    private List<Route> routes = new ArrayList<>();
    private WebhookProvider feishu = new WebhookProvider();
    private WebhookProvider dingtalk = new WebhookProvider();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes == null ? new ArrayList<>() : new ArrayList<>(routes);
    }

    public WebhookProvider getFeishu() {
        return feishu;
    }

    public void setFeishu(WebhookProvider feishu) {
        this.feishu = feishu == null ? new WebhookProvider() : feishu;
    }

    public WebhookProvider getDingtalk() {
        return dingtalk;
    }

    public void setDingtalk(WebhookProvider dingtalk) {
        this.dingtalk = dingtalk == null ? new WebhookProvider() : dingtalk;
    }

    public static class Route {

        private String key;
        private List<Destination> destinations = new ArrayList<>();

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public List<Destination> getDestinations() {
            return destinations;
        }

        public void setDestinations(List<Destination> destinations) {
            this.destinations = destinations == null ? new ArrayList<>() : new ArrayList<>(destinations);
        }
    }

    public static class Destination {

        private String id;
        private String providerKey;
        private String target;
        private Set<NotificationCapability> requiredCapabilities = Set.of();
        private Map<String, String> attributes = new LinkedHashMap<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getProviderKey() {
            return providerKey;
        }

        public void setProviderKey(String providerKey) {
            this.providerKey = providerKey;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public Set<NotificationCapability> getRequiredCapabilities() {
            return requiredCapabilities;
        }

        public void setRequiredCapabilities(Set<NotificationCapability> requiredCapabilities) {
            this.requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        }
    }

    public static class WebhookProvider {

        private Map<String, WebhookTarget> targets = new LinkedHashMap<>();

        public Map<String, WebhookTarget> getTargets() {
            return targets;
        }

        public void setTargets(Map<String, WebhookTarget> targets) {
            this.targets = targets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(targets);
        }
    }

    public static class WebhookTarget {

        private String webhookUrl;
        private String secret;
        private int timeoutMillis = 5000;

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }
}
