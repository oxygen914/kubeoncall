package com.kubeoncall.notification.application;

import java.util.Objects;

import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;

/** One provider delivery attempt with a deterministic idempotency identifier. */
public record NotificationDeliveryRequest(
        String deliveryId, NotificationMessage message, NotificationDestination destination) {

    public NotificationDeliveryRequest {
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new IllegalArgumentException("deliveryId must not be blank");
        }
        deliveryId = deliveryId.trim();
        message = Objects.requireNonNull(message, "message must not be null");
        destination = Objects.requireNonNull(destination, "destination must not be null");
    }
}
