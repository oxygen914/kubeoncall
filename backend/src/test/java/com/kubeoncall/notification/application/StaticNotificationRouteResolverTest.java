package com.kubeoncall.notification.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;
import com.kubeoncall.notification.domain.NotificationRoute;

class StaticNotificationRouteResolverTest {

    @Test
    void resolvesByLogicalRoutingKey() {
        NotificationRoute route = route("oncall");
        StaticNotificationRouteResolver resolver = new StaticNotificationRouteResolver(List.of(route));

        assertEquals(route, resolver.resolve(message("oncall")).orElseThrow());
        assertTrue(resolver.resolve(message("platform")).isEmpty());
        assertEquals(1, resolver.size());
    }

    @Test
    void rejectsDuplicateRouteKeys() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new StaticNotificationRouteResolver(List.of(route("oncall"), route("oncall"))));

        assertEquals("Duplicate notification route key: oncall", exception.getMessage());
    }

    private static NotificationRoute route(String key) {
        NotificationDestination destination = new NotificationDestination(
                "primary", "feishu", "infra-primary", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
        return new NotificationRoute(key, List.of(destination));
    }

    private static NotificationMessage message(String routingKey) {
        return new NotificationMessage(
                "evt-1",
                "alarm.firing",
                routingKey,
                NotificationPriority.HIGH,
                "NodeDown",
                "Node is unavailable",
                Map.of(),
                List.of(),
                Instant.parse("2026-07-30T01:00:00Z"));
    }
}
