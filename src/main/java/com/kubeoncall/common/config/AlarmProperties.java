package com.kubeoncall.common.config;

class AlarmProperties {

    private boolean enabled = true;
    private String policyLocation = "classpath:alarm-policies.yml";
    private String defaultSeverity = "P3";
    private long activeTtlSeconds = 86400;
    private long resolvedRetentionSeconds = 3600;
    private long p0DedupTtlSeconds = 1800;
    private long p1DedupTtlSeconds = 1200;
    private long p2DedupTtlSeconds = 600;
    private long p3DedupTtlSeconds = 300;
    private long infoDedupTtlSeconds = 120;
    private long nodeNotReadySuppressionTtlSeconds = 1800;
    private long escalationTtlSeconds = 3600;
    private long acknowledgementTtlSeconds = 86400;
    private long silenceApprovalTtlSeconds = 1800;
    private long p0RecoveryWindowSeconds = 900;
    private long p1RecoveryWindowSeconds = 600;
    private long p2RecoveryWindowSeconds = 300;
    private long p3RecoveryWindowSeconds = 120;
    private long infoRecoveryWindowSeconds = 0;
    private long recoveryStateTtlSeconds = 259200;
    private int recoveryBatchSize = 100;
    private boolean recoveryHealthCheckRequired = true;
    private String recoveryHealthCheckEndpoint;
    private int recoveryHealthCheckTimeoutMillis = 3000;
    private long recoveryConfirmationTimeoutSeconds = 86400;
    private long p0EscalationCount = 2;
    private long p1EscalationCount = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPolicyLocation() {
        return policyLocation;
    }

    public void setPolicyLocation(String policyLocation) {
        this.policyLocation = policyLocation;
    }

    public String getDefaultSeverity() {
        return defaultSeverity;
    }

    public void setDefaultSeverity(String defaultSeverity) {
        this.defaultSeverity = defaultSeverity;
    }

    public long getActiveTtlSeconds() {
        return activeTtlSeconds;
    }

    public void setActiveTtlSeconds(long activeTtlSeconds) {
        this.activeTtlSeconds = activeTtlSeconds;
    }

    public long getResolvedRetentionSeconds() {
        return resolvedRetentionSeconds;
    }

    public void setResolvedRetentionSeconds(long resolvedRetentionSeconds) {
        this.resolvedRetentionSeconds = resolvedRetentionSeconds;
    }

    public long getP0DedupTtlSeconds() {
        return p0DedupTtlSeconds;
    }

    public void setP0DedupTtlSeconds(long p0DedupTtlSeconds) {
        this.p0DedupTtlSeconds = p0DedupTtlSeconds;
    }

    public long getP1DedupTtlSeconds() {
        return p1DedupTtlSeconds;
    }

    public void setP1DedupTtlSeconds(long p1DedupTtlSeconds) {
        this.p1DedupTtlSeconds = p1DedupTtlSeconds;
    }

    public long getP2DedupTtlSeconds() {
        return p2DedupTtlSeconds;
    }

    public void setP2DedupTtlSeconds(long p2DedupTtlSeconds) {
        this.p2DedupTtlSeconds = p2DedupTtlSeconds;
    }

    public long getP3DedupTtlSeconds() {
        return p3DedupTtlSeconds;
    }

    public void setP3DedupTtlSeconds(long p3DedupTtlSeconds) {
        this.p3DedupTtlSeconds = p3DedupTtlSeconds;
    }

    public long getInfoDedupTtlSeconds() {
        return infoDedupTtlSeconds;
    }

    public void setInfoDedupTtlSeconds(long infoDedupTtlSeconds) {
        this.infoDedupTtlSeconds = infoDedupTtlSeconds;
    }

    public long getNodeNotReadySuppressionTtlSeconds() {
        return nodeNotReadySuppressionTtlSeconds;
    }

    public void setNodeNotReadySuppressionTtlSeconds(long nodeNotReadySuppressionTtlSeconds) {
        this.nodeNotReadySuppressionTtlSeconds = nodeNotReadySuppressionTtlSeconds;
    }

    public long getEscalationTtlSeconds() {
        return escalationTtlSeconds;
    }

    public void setEscalationTtlSeconds(long escalationTtlSeconds) {
        this.escalationTtlSeconds = escalationTtlSeconds;
    }

    public long getAcknowledgementTtlSeconds() {
        return acknowledgementTtlSeconds;
    }

    public void setAcknowledgementTtlSeconds(long acknowledgementTtlSeconds) {
        this.acknowledgementTtlSeconds = acknowledgementTtlSeconds;
    }

    public long getSilenceApprovalTtlSeconds() {
        return silenceApprovalTtlSeconds;
    }

    public void setSilenceApprovalTtlSeconds(long silenceApprovalTtlSeconds) {
        this.silenceApprovalTtlSeconds = silenceApprovalTtlSeconds;
    }

    public long getP0RecoveryWindowSeconds() {
        return p0RecoveryWindowSeconds;
    }

    public void setP0RecoveryWindowSeconds(long p0RecoveryWindowSeconds) {
        this.p0RecoveryWindowSeconds = p0RecoveryWindowSeconds;
    }

    public long getP1RecoveryWindowSeconds() {
        return p1RecoveryWindowSeconds;
    }

    public void setP1RecoveryWindowSeconds(long p1RecoveryWindowSeconds) {
        this.p1RecoveryWindowSeconds = p1RecoveryWindowSeconds;
    }

    public long getP2RecoveryWindowSeconds() {
        return p2RecoveryWindowSeconds;
    }

    public void setP2RecoveryWindowSeconds(long p2RecoveryWindowSeconds) {
        this.p2RecoveryWindowSeconds = p2RecoveryWindowSeconds;
    }

    public long getP3RecoveryWindowSeconds() {
        return p3RecoveryWindowSeconds;
    }

    public void setP3RecoveryWindowSeconds(long p3RecoveryWindowSeconds) {
        this.p3RecoveryWindowSeconds = p3RecoveryWindowSeconds;
    }

    public long getInfoRecoveryWindowSeconds() {
        return infoRecoveryWindowSeconds;
    }

    public void setInfoRecoveryWindowSeconds(long infoRecoveryWindowSeconds) {
        this.infoRecoveryWindowSeconds = infoRecoveryWindowSeconds;
    }

    public long getRecoveryStateTtlSeconds() {
        return recoveryStateTtlSeconds;
    }

    public void setRecoveryStateTtlSeconds(long recoveryStateTtlSeconds) {
        this.recoveryStateTtlSeconds = recoveryStateTtlSeconds;
    }

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    public boolean isRecoveryHealthCheckRequired() {
        return recoveryHealthCheckRequired;
    }

    public void setRecoveryHealthCheckRequired(boolean recoveryHealthCheckRequired) {
        this.recoveryHealthCheckRequired = recoveryHealthCheckRequired;
    }

    public String getRecoveryHealthCheckEndpoint() {
        return recoveryHealthCheckEndpoint;
    }

    public void setRecoveryHealthCheckEndpoint(String recoveryHealthCheckEndpoint) {
        this.recoveryHealthCheckEndpoint = recoveryHealthCheckEndpoint;
    }

    public int getRecoveryHealthCheckTimeoutMillis() {
        return recoveryHealthCheckTimeoutMillis;
    }

    public void setRecoveryHealthCheckTimeoutMillis(int recoveryHealthCheckTimeoutMillis) {
        this.recoveryHealthCheckTimeoutMillis = recoveryHealthCheckTimeoutMillis;
    }

    public long getRecoveryConfirmationTimeoutSeconds() {
        return recoveryConfirmationTimeoutSeconds;
    }

    public void setRecoveryConfirmationTimeoutSeconds(long recoveryConfirmationTimeoutSeconds) {
        this.recoveryConfirmationTimeoutSeconds = recoveryConfirmationTimeoutSeconds;
    }

    public long getP0EscalationCount() {
        return p0EscalationCount;
    }

    public void setP0EscalationCount(long p0EscalationCount) {
        this.p0EscalationCount = p0EscalationCount;
    }

    public long getP1EscalationCount() {
        return p1EscalationCount;
    }

    public void setP1EscalationCount(long p1EscalationCount) {
        this.p1EscalationCount = p1EscalationCount;
    }
}
