package com.kubeoncall.alarm.ingest;

/** Raised when the durable inbox declines an event without reporting it as a duplicate. */
public class AlarmIngestionRejectedException extends RuntimeException {

    public AlarmIngestionRejectedException(String message) {
        super(message);
    }
}
