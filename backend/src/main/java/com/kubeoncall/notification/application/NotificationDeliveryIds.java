package com.kubeoncall.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;

/** Stable bounded identifiers shared by submission, persistence and provider retries. */
public final class NotificationDeliveryIds {

    private NotificationDeliveryIds() {}

    public static String deliveryKey(NotificationMessage message, NotificationDestination destination) {
        String canonical = String.join(
                "\u0000",
                message.eventId(),
                message.routingKey(),
                destination.id(),
                destination.providerKey(),
                destination.target());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String publicId(String deliveryKey) {
        if (deliveryKey == null || deliveryKey.length() < 32) {
            throw new IllegalArgumentException("deliveryKey must contain at least 32 characters");
        }
        return "ndlv_" + deliveryKey.substring(0, 32);
    }

    public static String requestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.trim();
        if (normalized.length() <= 64) {
            return normalized;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "nreq_" + HexFormat.of().formatHex(digest).substring(0, 59);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
