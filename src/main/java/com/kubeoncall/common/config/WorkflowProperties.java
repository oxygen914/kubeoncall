package com.kubeoncall.common.config;

class WorkflowProperties {

    private long alarmDedupTtlSeconds = 600;
    private long nodeTimeoutMillis = 3000;

    public long getAlarmDedupTtlSeconds() {
        return alarmDedupTtlSeconds;
    }

    public void setAlarmDedupTtlSeconds(long alarmDedupTtlSeconds) {
        this.alarmDedupTtlSeconds = alarmDedupTtlSeconds;
    }

    public long getNodeTimeoutMillis() {
        return nodeTimeoutMillis;
    }

    public void setNodeTimeoutMillis(long nodeTimeoutMillis) {
        this.nodeTimeoutMillis = nodeTimeoutMillis;
    }
}
