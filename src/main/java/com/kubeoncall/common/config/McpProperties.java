package com.kubeoncall.common.config;

class McpProperties {

    private boolean enabled = true;
    private String serverName = "local-mcp";
    private String endpoint = "http://localhost:18080/mcp/call";
    private String apiKey = "";
    private int timeoutMillis = 3000;
    private boolean discoveryEnabled = false;
    private String discoveryEndpoint = "";
    private int discoveryCacheSeconds = 60;
    private boolean dynamicInvocationEnabled = false;
    private int dynamicMaxTools = 3;

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

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
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
}
