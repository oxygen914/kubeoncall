package com.kubeoncall.notification.domain;

/** Optional delivery features that a notification provider can advertise. */
public enum NotificationCapability {
    GROUP_WEBHOOK,
    DIRECT_MESSAGE,
    MENTION_USER,
    CARD_UPDATE,
    INTERACTIVE_CALLBACK,
    MESSAGE_RECALL
}
