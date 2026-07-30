package com.kubeoncall.common.config;

import java.util.ArrayList;
import java.util.List;

class McpProperties {

    private boolean enabled = true;
    private String serverName = "local-mcp";
    private String endpoint = "http://localhost:18080/mcp/call";
    private String apiKey = "";
    private String authMode = "static_bearer";
    private String oauthTokenEndpoint = "";
    private String oauthClientId = "";
    private String oauthClientSecret = "";
    private String oauthClientAuthMethod = "client_secret_post";
    private String oauthScope = "";
    private String oauthResource = "";
    private int oauthRefreshSkewSeconds = 30;
    private int timeoutMillis = 3000;
    private String protocol = "custom";
    private String protocolVersion = "2025-11-25";
    private String clientName = "kubeoncall";
    private String clientVersion = "1.0.0";
    private boolean discoveryEnabled = false;
    private String discoveryEndpoint = "";
    private int discoveryCacheSeconds = 60;
    private int discoveryMaxPages = 20;
    private boolean dynamicInvocationEnabled = false;
    private int dynamicMaxTools = 3;
    private int maxResponseBytes = 262144;
    private List<String> allowedTools = new ArrayList<>(List.of(
            "knowledge.searchSop",
            "topology.getServiceTopology",
            "cmdb.getServiceMetadata",
            "kubernetes.describeResource",
            "prometheus.queryRange",
            "alerts.getActiveAlerts",
            "changes.getRecentChanges",
            "inventory.*",
            "metrics.*"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        this.authMode = authMode;
    }

    public String getOauthTokenEndpoint() {
        return oauthTokenEndpoint;
    }

    public void setOauthTokenEndpoint(String oauthTokenEndpoint) {
        this.oauthTokenEndpoint = oauthTokenEndpoint;
    }

    public String getOauthClientId() {
        return oauthClientId;
    }

    public void setOauthClientId(String oauthClientId) {
        this.oauthClientId = oauthClientId;
    }

    public String getOauthClientSecret() {
        return oauthClientSecret;
    }

    public void setOauthClientSecret(String oauthClientSecret) {
        this.oauthClientSecret = oauthClientSecret;
    }

    public String getOauthClientAuthMethod() {
        return oauthClientAuthMethod;
    }

    public void setOauthClientAuthMethod(String oauthClientAuthMethod) {
        this.oauthClientAuthMethod = oauthClientAuthMethod;
    }

    public String getOauthScope() {
        return oauthScope;
    }

    public void setOauthScope(String oauthScope) {
        this.oauthScope = oauthScope;
    }

    public String getOauthResource() {
        return oauthResource;
    }

    public void setOauthResource(String oauthResource) {
        this.oauthResource = oauthResource;
    }

    public int getOauthRefreshSkewSeconds() {
        return oauthRefreshSkewSeconds;
    }

    public void setOauthRefreshSkewSeconds(int oauthRefreshSkewSeconds) {
        this.oauthRefreshSkewSeconds = oauthRefreshSkewSeconds;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    public String getDiscoveryEndpoint() {
        return discoveryEndpoint;
    }

    public void setDiscoveryEndpoint(String discoveryEndpoint) {
        this.discoveryEndpoint = discoveryEndpoint;
    }

    public int getDiscoveryCacheSeconds() {
        return discoveryCacheSeconds;
    }

    public void setDiscoveryCacheSeconds(int discoveryCacheSeconds) {
        this.discoveryCacheSeconds = discoveryCacheSeconds;
    }

    public int getDiscoveryMaxPages() {
        return discoveryMaxPages;
    }

    public void setDiscoveryMaxPages(int discoveryMaxPages) {
        this.discoveryMaxPages = discoveryMaxPages;
    }

    public boolean isDynamicInvocationEnabled() {
        return dynamicInvocationEnabled;
    }

    public void setDynamicInvocationEnabled(boolean dynamicInvocationEnabled) {
        this.dynamicInvocationEnabled = dynamicInvocationEnabled;
    }

    public int getDynamicMaxTools() {
        return dynamicMaxTools;
    }

    public void setDynamicMaxTools(int dynamicMaxTools) {
        this.dynamicMaxTools = dynamicMaxTools;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public List<String> getAllowedTools() {
        return List.copyOf(allowedTools);
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools == null ? new ArrayList<>() : new ArrayList<>(allowedTools);
    }
}
