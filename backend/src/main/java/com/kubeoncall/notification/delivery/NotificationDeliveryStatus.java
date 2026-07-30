package com.kubeoncall.notification.delivery;

/** Durable state of one physical notification destination. */
public enum NotificationDeliveryStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    DELIVERED,
    FAILED,
    DEAD_LETTER;

    public boolean terminal() {
        return this == DELIVERED || this == FAILED || this == DEAD_LETTER;
    }
}
