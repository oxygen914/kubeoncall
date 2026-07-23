package com.kubeoncall.common.config;

/** Operational limits for durable knowledge-import maintenance. */
class KnowledgeProperties {

    private long orphanGraceSeconds = 3600;

    public long getOrphanGraceSeconds() {
        return orphanGraceSeconds;
    }

    public void setOrphanGraceSeconds(long orphanGraceSeconds) {
        this.orphanGraceSeconds = orphanGraceSeconds;
    }
}
