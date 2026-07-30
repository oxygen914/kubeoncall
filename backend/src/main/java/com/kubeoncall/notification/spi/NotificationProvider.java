package com.kubeoncall.notification.spi;

import java.util.Set;

import com.kubeoncall.notification.application.NotificationDeliveryRequest;
import com.kubeoncall.notification.domain.NotificationCapability;

/** Converts one canonical notification request into a provider-specific external delivery. */
public interface NotificationProvider {

    String providerKey();

    Set<NotificationCapability> capabilities();

    SendResult send(NotificationDeliveryRequest request);

    /** Provider response fields that are safe to persist and expose through operational APIs. */
    record SendResult(String externalMessageId, String providerCode, String detail) {}
}
