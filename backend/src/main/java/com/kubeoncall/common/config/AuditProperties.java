package com.kubeoncall.common.config;

class AuditProperties {

    private String repository = "redis";
    private int retentionHours = 168;

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public int getRetentionHours() {
        return retentionHours;
    }

    public void setRetentionHours(int retentionHours) {
        this.retentionHours = retentionHours;
    }
}
