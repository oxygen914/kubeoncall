package com.kubeoncall.notification.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationRoute;
import com.kubeoncall.notification.spi.NotificationRouteResolver;

/** Immutable route resolver used until routes move to governed configuration or persistent storage. */
public final class StaticNotificationRouteResolver implements NotificationRouteResolver {

    private final Map<String, NotificationRoute> routes;

    public StaticNotificationRouteResolver(Collection<NotificationRoute> routes) {
        Map<String, NotificationRoute> indexed = new LinkedHashMap<>();
        if (routes != null) {
            for (NotificationRoute route : routes) {
                if (route == null) {
                    throw new IllegalArgumentException("Notification route must not be null");
                }
                NotificationRoute previous = indexed.putIfAbsent(route.key(), route);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate notification route key: " + route.key());
                }
            }
        }
        this.routes = Map.copyOf(indexed);
    }

    @Override
    public Optional<NotificationRoute> resolve(NotificationMessage message) {
        if (message == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(routes.get(message.routingKey()));
    }

    public int size() {
        return routes.size();
    }
}
