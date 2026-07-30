package com.kubeoncall.notification.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A logical business route such as {@code oncall}, mapped to one or more physical destinations. */
public record NotificationRoute(String key, List<NotificationDestination> destinations) {

    public NotificationRoute {
        key = requireText(key, "route key");
        if (destinations == null || destinations.isEmpty()) {
            throw new IllegalArgumentException("route destinations must not be empty");
        }
        List<NotificationDestination> copied = new ArrayList<>(destinations.size());
        Set<String> destinationIds = new HashSet<>();
        for (NotificationDestination destination : destinations) {
            if (destination == null) {
                throw new IllegalArgumentException("route destination must not be null");
            }
            if (!destinationIds.add(destination.id())) {
                throw new IllegalArgumentException(
                        "Duplicate destination id in route " + key + ": " + destination.id());
            }
            copied.add(destination);
        }
        destinations = List.copyOf(copied);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
