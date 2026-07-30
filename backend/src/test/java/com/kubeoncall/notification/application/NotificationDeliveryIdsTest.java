package com.kubeoncall.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;

class NotificationDeliveryIdsTest {

    @Test
    void createsStableBoundedIdsAndSeparatesDestinations() {
        NotificationMessage message = message();
        NotificationDestination first = destination("primary");
        NotificationDestination second = destination("backup");

        String firstKey = NotificationDeliveryIds.deliveryKey(message, first);

        assertThat(firstKey).hasSize(64);
        assertThat(firstKey).isEqualTo(NotificationDeliveryIds.deliveryKey(message, first));
        assertThat(firstKey).isNotEqualTo(NotificationDeliveryIds.deliveryKey(message, second));
        assertThat(NotificationDeliveryIds.publicId(firstKey))
                .startsWith("ndlv_")
                .hasSize(37);
        assertThat(NotificationDeliveryIds.requestId("x".repeat(200)))
                .startsWith("nreq_")
                .hasSize(64)
                .isEqualTo(NotificationDeliveryIds.requestId("x".repeat(200)));
    }

    private static NotificationMessage message() {
        return new NotificationMessage(
                "event-1",
                "alarm.firing",
                "oncall",
                NotificationPriority.HIGH,
                "NodeDown",
                "node is unavailable",
                Map.of(),
                List.of(),
                Instant.parse("2026-07-30T01:00:00Z"));
    }

    private static NotificationDestination destination(String id) {
        return new NotificationDestination(
                id, "feishu", id + "-target", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
    }
}
