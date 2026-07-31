package com.kubeoncall.notification.application;

import java.util.List;

/** Result of durably accepting one logical notification event. */
public record NotificationPublishResult(Status status, String eventId, List<String> deliveryIds) {

    public enum Status {
        QUEUED,
        ALREADY_QUEUED,
        NO_ROUTE,
        DISABLED
    }

    public NotificationPublishResult {
        deliveryIds = deliveryIds == null ? List.of() : List.copyOf(deliveryIds);
    }

    public static NotificationPublishResult disabled(String eventId) {
        return new NotificationPublishResult(Status.DISABLED, eventId, List.of());
    }

    public static NotificationPublishResult noRoute(String eventId) {
        return new NotificationPublishResult(Status.NO_ROUTE, eventId, List.of());
    }
}
