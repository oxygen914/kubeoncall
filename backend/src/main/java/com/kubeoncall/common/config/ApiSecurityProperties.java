package com.kubeoncall.common.config;

class ApiSecurityProperties {

    private boolean enabled;
    private String viewerToken;
    private String operatorToken;
    private String adminToken;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getViewerToken() {
        return viewerToken;
    }

    public void setViewerToken(String viewerToken) {
        this.viewerToken = viewerToken;
    }

    public String getOperatorToken() {
        return operatorToken;
    }

    public void setOperatorToken(String operatorToken) {
        this.operatorToken = operatorToken;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }
}
