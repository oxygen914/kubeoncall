package com.kubeoncall.notification.delivery;

import java.time.Instant;

import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;

/** Persisted projection for one message delivered to one physical destination. */
public record NotificationDeliveryRecord(
        long id,
        String publicId,
        String deliveryKey,
        NotificationMessage message,
        NotificationDestination destination,
        String operation,
        NotificationDeliveryStatus status,
        int attempt,
        int replayCount,
        boolean retryable,
        String externalMessageId,
        String providerCode,
        String lastErrorCode,
        String lastErrorSummary,
        String requestId,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt) {}
