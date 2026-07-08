package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMemoryInjectorTest {

    @Test
    void shouldInjectOnlyServiceFactsAndKnownPitfallsWithFreshnessHint() {
        MemoryService memoryService = mock(MemoryService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setStaleAfterDays(1);
        DefaultMemoryInjector injector = new DefaultMemoryInjector(memoryService, properties);
        MemoryEntry fact = entry("m1", MemoryType.SERVICE_FACT, Instant.now());
        MemoryEntry pitfall = entry("m2", MemoryType.KNOWN_PITFALL, Instant.now().minus(3, ChronoUnit.DAYS));
        MemoryEntry incident = entry("m3", MemoryType.INCIDENT_SUMMARY, Instant.now());
        when(memoryService.search("payment", Map.of(), 5)).thenReturn(List.of(fact, pitfall, incident));

        MemoryInjection injection = injector.inject("payment", Map.of());

        assertEquals(2, injection.entries().size());
        assertTrue(injection.prompt().contains("Verify current cluster state"));
        assertTrue(injection.prompt().contains("SERVICE_FACT"));
        assertTrue(injection.prompt().contains("stale"));
        assertFalse(injection.prompt().contains("INCIDENT_SUMMARY"));
    }

    private MemoryEntry entry(String id, MemoryType type, Instant updatedAt) {
        return new MemoryEntry(
                id,
                type,
                MemoryScope.SERVICE,
                "payment note",
                "payment memory content",
                "payment-service",
                null,
                null,
                updatedAt,
                updatedAt,
                Map.of()
        );
    }
}
