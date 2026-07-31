package com.kubeoncall.notification.application;

import java.util.List;

import com.kubeoncall.notification.domain.NotificationMessage;

/** Aggregate result for all physical destinations selected by one logical route. */
public record NotificationDispatchResult(
        String eventId, String routingKey, Status status, List<NotificationDeliveryResult> deliveries) {

    public enum Status {
        DELIVERED,
        PARTIAL_FAILURE,
        FAILED,
        NO_ROUTE
    }

    public NotificationDispatchResult {
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
    }

    public static NotificationDispatchResult noRoute(NotificationMessage message) {
        return new NotificationDispatchResult(message.eventId(), message.routingKey(), Status.NO_ROUTE, List.of());
    }

    public static NotificationDispatchResult completed(
            NotificationMessage message, List<NotificationDeliveryResult> deliveries) {
        List<NotificationDeliveryResult> copied = List.copyOf(deliveries);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("completed dispatch must contain at least one delivery");
        }
        long deliveredCount =
                copied.stream().filter(NotificationDeliveryResult::delivered).count();
        Status status;
        if (deliveredCount == copied.size()) {
            status = Status.DELIVERED;
        } else if (deliveredCount == 0) {
            status = Status.FAILED;
        } else {
            status = Status.PARTIAL_FAILURE;
        }
        return new NotificationDispatchResult(message.eventId(), message.routingKey(), status, copied);
    }

    public boolean hasRetryableFailure() {
        return deliveries.stream().anyMatch(result -> !result.delivered() && result.retryable());
    }
}
