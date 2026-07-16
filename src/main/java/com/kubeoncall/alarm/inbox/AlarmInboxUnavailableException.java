package com.kubeoncall.alarm.inbox;

/** Raised when an alarm cannot be durably accepted into the configured inbox. */
public class AlarmInboxUnavailableException extends RuntimeException {

    public AlarmInboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
