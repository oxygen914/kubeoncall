package com.kubeoncall.notification.domain;

/** Platform-neutral urgency used by routing and provider-specific renderers. */
public enum NotificationPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}
