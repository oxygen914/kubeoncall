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
    private long p0EscalationAfterSeconds = 600;
    private long p1EscalationAfterSeconds = 1800;
    private int policyVersionHistoryLimit = 20;
    private long p1AggregationWindowSeconds = 300;
    private long p2AggregationWindowSeconds = 900;
    private long p3AggregationWindowSeconds = 3600;
    private boolean alertmanagerWebhookEnabled = true;
    private String alertmanagerWebhookAuthMode = "bearer";
    private String alertmanagerWebhookToken;
    private String alertmanagerWebhookTokenFile;
    private int alertmanagerWebhookMaxAlertsPerRequest = 100;
    private int alertmanagerWebhookMaxPayloadBytes = 1048576;
    private String inboxStreamKey = "kubeoncall:alarm:inbox";
    private String inboxConsumerGroup = "kubeoncall-alarm-workers";
    private String inboxDeadLetterKey = "kubeoncall:alarm:dead-letter";
    private int inboxMaxRetries = 5;
    private long inboxRetentionHours = 168;
    private boolean inboxWorkerEnabled = true;
    private int inboxWorkerBatchSize = 10;
    private long inboxWorkerBlockMillis = 1000;
    private long inboxPendingClaimIdleMillis = 60000;

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

    public long getP0EscalationAfterSeconds() {
        return p0EscalationAfterSeconds;
    }

    public void setP0EscalationAfterSeconds(long p0EscalationAfterSeconds) {
        this.p0EscalationAfterSeconds = p0EscalationAfterSeconds;
    }

    public long getP1EscalationAfterSeconds() {
        return p1EscalationAfterSeconds;
    }

    public void setP1EscalationAfterSeconds(long p1EscalationAfterSeconds) {
        this.p1EscalationAfterSeconds = p1EscalationAfterSeconds;
    }

    public int getPolicyVersionHistoryLimit() {
        return policyVersionHistoryLimit;
    }

    public void setPolicyVersionHistoryLimit(int policyVersionHistoryLimit) {
        this.policyVersionHistoryLimit = policyVersionHistoryLimit;
    }

    public long getP1AggregationWindowSeconds() {
        return p1AggregationWindowSeconds;
    }

    public void setP1AggregationWindowSeconds(long p1AggregationWindowSeconds) {
        this.p1AggregationWindowSeconds = p1AggregationWindowSeconds;
    }

    public long getP2AggregationWindowSeconds() {
        return p2AggregationWindowSeconds;
    }

    public void setP2AggregationWindowSeconds(long p2AggregationWindowSeconds) {
        this.p2AggregationWindowSeconds = p2AggregationWindowSeconds;
    }

    public long getP3AggregationWindowSeconds() {
        return p3AggregationWindowSeconds;
    }

    public void setP3AggregationWindowSeconds(long p3AggregationWindowSeconds) {
        this.p3AggregationWindowSeconds = p3AggregationWindowSeconds;
    }

    public boolean isAlertmanagerWebhookEnabled() {
        return alertmanagerWebhookEnabled;
    }

    public void setAlertmanagerWebhookEnabled(boolean alertmanagerWebhookEnabled) {
        this.alertmanagerWebhookEnabled = alertmanagerWebhookEnabled;
    }

    public String getAlertmanagerWebhookAuthMode() {
        return alertmanagerWebhookAuthMode;
    }

    public void setAlertmanagerWebhookAuthMode(String alertmanagerWebhookAuthMode) {
        this.alertmanagerWebhookAuthMode = alertmanagerWebhookAuthMode;
    }

    public String getAlertmanagerWebhookToken() {
        return alertmanagerWebhookToken;
    }

    public void setAlertmanagerWebhookToken(String alertmanagerWebhookToken) {
        this.alertmanagerWebhookToken = alertmanagerWebhookToken;
    }

    public String getAlertmanagerWebhookTokenFile() {
        return alertmanagerWebhookTokenFile;
    }

    public void setAlertmanagerWebhookTokenFile(String alertmanagerWebhookTokenFile) {
        this.alertmanagerWebhookTokenFile = alertmanagerWebhookTokenFile;
    }

    public int getAlertmanagerWebhookMaxAlertsPerRequest() {
        return alertmanagerWebhookMaxAlertsPerRequest;
    }

    public void setAlertmanagerWebhookMaxAlertsPerRequest(int alertmanagerWebhookMaxAlertsPerRequest) {
        this.alertmanagerWebhookMaxAlertsPerRequest = alertmanagerWebhookMaxAlertsPerRequest;
    }

    public int getAlertmanagerWebhookMaxPayloadBytes() {
        return alertmanagerWebhookMaxPayloadBytes;
    }

    public void setAlertmanagerWebhookMaxPayloadBytes(int alertmanagerWebhookMaxPayloadBytes) {
        this.alertmanagerWebhookMaxPayloadBytes = alertmanagerWebhookMaxPayloadBytes;
    }

    public String getInboxStreamKey() {
        return inboxStreamKey;
    }

    public void setInboxStreamKey(String inboxStreamKey) {
        this.inboxStreamKey = inboxStreamKey;
    }

    public String getInboxConsumerGroup() {
        return inboxConsumerGroup;
    }

    public void setInboxConsumerGroup(String inboxConsumerGroup) {
        this.inboxConsumerGroup = inboxConsumerGroup;
    }

    public String getInboxDeadLetterKey() {
        return inboxDeadLetterKey;
    }

    public void setInboxDeadLetterKey(String inboxDeadLetterKey) {
        this.inboxDeadLetterKey = inboxDeadLetterKey;
    }

    public int getInboxMaxRetries() {
        return inboxMaxRetries;
    }

    public void setInboxMaxRetries(int inboxMaxRetries) {
        this.inboxMaxRetries = inboxMaxRetries;
    }

    public long getInboxRetentionHours() {
        return inboxRetentionHours;
    }

    public void setInboxRetentionHours(long inboxRetentionHours) {
        this.inboxRetentionHours = inboxRetentionHours;
    }

    public boolean isInboxWorkerEnabled() {
        return inboxWorkerEnabled;
    }

    public void setInboxWorkerEnabled(boolean inboxWorkerEnabled) {
        this.inboxWorkerEnabled = inboxWorkerEnabled;
    }

    public int getInboxWorkerBatchSize() {
        return inboxWorkerBatchSize;
    }

    public void setInboxWorkerBatchSize(int inboxWorkerBatchSize) {
        this.inboxWorkerBatchSize = inboxWorkerBatchSize;
    }

    public long getInboxWorkerBlockMillis() {
        return inboxWorkerBlockMillis;
    }

    public void setInboxWorkerBlockMillis(long inboxWorkerBlockMillis) {
        this.inboxWorkerBlockMillis = inboxWorkerBlockMillis;
    }

    public long getInboxPendingClaimIdleMillis() {
        return inboxPendingClaimIdleMillis;
    }

    public void setInboxPendingClaimIdleMillis(long inboxPendingClaimIdleMillis) {
        this.inboxPendingClaimIdleMillis = inboxPendingClaimIdleMillis;
    }
}
