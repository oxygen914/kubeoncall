package com.kubeoncall.domain.task;

public enum TaskType {
    QUERY_LOGS,
    QUERY_METRICS,
    PATCH_CONFIG,
    RESTART_SERVICE,
    SCALE_WORKLOAD,
    CLEAN_DATA,
    EXECUTE_SCRIPT
}
