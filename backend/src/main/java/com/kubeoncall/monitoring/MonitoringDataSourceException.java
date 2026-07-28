package com.kubeoncall.monitoring;

public class MonitoringDataSourceException extends RuntimeException {

    public MonitoringDataSourceException(String message) {
        super(message);
    }

    public MonitoringDataSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
