package com.kubeoncall.common.config;

class DependencyCircuitBreakerProperties {

    private boolean enabled = true;
    private int failureThreshold = 5;
    private int resetTimeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getResetTimeoutSeconds() {
        return resetTimeoutSeconds;
    }

    public void setResetTimeoutSeconds(int resetTimeoutSeconds) {
        this.resetTimeoutSeconds = resetTimeoutSeconds;
    }
}
