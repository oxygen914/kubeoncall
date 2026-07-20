package com.kubeoncall.common.config;

class ApprovalProperties {

    private boolean enabled = true;
    private int callbackTimeoutSeconds = 1800;
    private int resumeLeaseSeconds = 300;

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

    public int getResumeLeaseSeconds() {
        return resumeLeaseSeconds;
    }

    public void setResumeLeaseSeconds(int resumeLeaseSeconds) {
        this.resumeLeaseSeconds = resumeLeaseSeconds;
    }
}
