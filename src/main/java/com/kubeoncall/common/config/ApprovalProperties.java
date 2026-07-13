package com.kubeoncall.common.config;

class ApprovalProperties {

    private boolean enabled = true;
    private int callbackTimeoutSeconds = 1800;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCallbackTimeoutSeconds() {
        return callbackTimeoutSeconds;
    }

    public void setCallbackTimeoutSeconds(int callbackTimeoutSeconds) {
        this.callbackTimeoutSeconds = callbackTimeoutSeconds;
    }
}
