package com.kubeoncall.notification.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.spi.NotificationProvider;

/** Immutable provider registry. Duplicate provider keys fail fast during application startup. */
public final class NotificationProviderRegistry {

    private final Map<String, Registration> providers;

    public NotificationProviderRegistry(Collection<? extends NotificationProvider> providers) {
        Map<String, Registration> indexed = new LinkedHashMap<>();
        if (providers != null) {
            for (NotificationProvider provider : providers) {
                if (provider == null) {
                    throw new IllegalArgumentException("Notification provider must not be null");
                }
                String key = normalizeKey(provider.providerKey());
                Set<NotificationCapability> capabilities = provider.capabilities();
                if (capabilities == null) {
                    throw new IllegalArgumentException("Notification provider capabilities must not be null: " + key);
                }
                Registration registration = new Registration(provider, Set.copyOf(capabilities));
                Registration previous = indexed.putIfAbsent(key, registration);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate notification provider key: " + key);
                }
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public Optional<Registration> find(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(normalizeKey(providerKey)));
    }

    public int size() {
        return providers.size();
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("providerKey must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record Registration(NotificationProvider provider, Set<NotificationCapability> capabilities) {}
}
