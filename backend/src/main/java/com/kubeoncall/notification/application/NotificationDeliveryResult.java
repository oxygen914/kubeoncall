package com.kubeoncall.notification.application;

/** Normalized outcome for one destination, independent of provider response shape. */
public record NotificationDeliveryResult(
        String deliveryId,
        String providerKey,
        String destinationId,
        Status status,
        String externalMessageId,
        String code,
        String detail,
        boolean retryable) {

    public enum Status {
        DELIVERED,
        FAILED
    }

    public static NotificationDeliveryResult delivered(
            NotificationDeliveryRequest request,
            String providerKey,
            String externalMessageId,
            String providerCode,
            String detail) {
        return new NotificationDeliveryResult(
                request.deliveryId(),
                providerKey,
                request.destination().id(),
                Status.DELIVERED,
                blankToNull(externalMessageId),
                blankToNull(providerCode),
                blankToNull(detail),
                false);
    }

    public static NotificationDeliveryResult failed(
            NotificationDeliveryRequest request, String providerKey, String code, String detail, boolean retryable) {
        return new NotificationDeliveryResult(
                request.deliveryId(),
                providerKey,
                request.destination().id(),
                Status.FAILED,
                null,
                requireText(code, "delivery error code"),
                requireText(detail, "delivery error detail"),
                retryable);
    }

    public boolean delivered() {
        return status == Status.DELIVERED;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
