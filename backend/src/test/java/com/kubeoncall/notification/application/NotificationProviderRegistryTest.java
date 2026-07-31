package com.kubeoncall.notification.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.spi.NotificationProvider;

class NotificationProviderRegistryTest {

    @Test
    void indexesProvidersByNormalizedKeyAndSnapshotsCapabilities() {
        MutableProvider provider = new MutableProvider(" FeiShu ");
        provider.capabilities.add(NotificationCapability.GROUP_WEBHOOK);
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(provider));

        provider.capabilities.clear();

        NotificationProviderRegistry.Registration registration =
                registry.find("FEISHU").orElseThrow();
        assertSame(provider, registration.provider());
        assertEquals(Set.of(NotificationCapability.GROUP_WEBHOOK), registration.capabilities());
        assertEquals(1, registry.size());
    }

    @Test
    void rejectsDuplicateProviderKeysIgnoringCase() {
        NotificationProvider first = new MutableProvider("feishu");
        NotificationProvider second = new MutableProvider("FEISHU");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> new NotificationProviderRegistry(List.of(first, second)));

        assertEquals("Duplicate notification provider key: feishu", exception.getMessage());
    }

    private static final class MutableProvider implements NotificationProvider {

        private final String key;
        private final Set<NotificationCapability> capabilities = new java.util.HashSet<>();

        private MutableProvider(String key) {
            this.key = key;
        }

        @Override
        public String providerKey() {
            return key;
        }

        @Override
        public Set<NotificationCapability> capabilities() {
            return capabilities;
        }

        @Override
        public SendResult send(NotificationDeliveryRequest request) {
            return new SendResult(null, "0", "ok");
        }
    }
}
