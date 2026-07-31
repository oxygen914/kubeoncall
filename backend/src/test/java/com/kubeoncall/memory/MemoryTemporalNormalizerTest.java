package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

class MemoryTemporalNormalizerTest {

    @Test
    void shouldNormalizeDeterministicChineseAndEnglishDates() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setTemporalNormalizationZone("Asia/Shanghai");
        MemoryTemporalNormalizer normalizer = new MemoryTemporalNormalizer(properties);
        Instant createdAt = Instant.parse("2026-07-10T01:00:00Z");
        MemoryEntry entry =
                entry("昨天 OOM；3天前扩容；the day before yesterday restarted；last week deployed", createdAt, Map.of());

        MemoryEntry normalized = normalizer.normalize(entry, Instant.parse("2026-07-10T02:00:00Z"));

        assertEquals(
                "2026-07-09 OOM；2026-07-07扩容；2026-07-08 restarted；2026-06-29 to 2026-07-05 deployed",
                normalized.content());
        assertEquals("normalized", normalized.metadata().get("temporal_normalization_status"));
        assertEquals("Asia/Shanghai", normalized.metadata().get("temporal_normalization_zone"));
        assertEquals("4", normalized.metadata().get("temporal_normalization_count"));
        assertEquals("2026-07-10", normalized.metadata().get("temporal_normalization_reference"));
    }

    @Test
    void shouldPreserveAmbiguousDateAndMarkUnresolved() {
        MemoryTemporalNormalizer normalizer = new MemoryTemporalNormalizer(new KubeOnCallProperties());
        MemoryEntry entry = entry("最近 payment-service 出现过超时", Instant.parse("2026-07-10T01:00:00Z"), Map.of());

        MemoryEntry normalized = normalizer.normalize(entry, Instant.parse("2026-07-10T02:00:00Z"));

        assertEquals(entry.content(), normalized.content());
        assertEquals("unresolved", normalized.metadata().get("temporal_normalization_status"));
        assertEquals("最近", normalized.metadata().get("temporal_unresolved_expressions"));
        assertSame(normalized, normalizer.normalize(normalized, Instant.parse("2026-07-11T02:00:00Z")));
    }

    @Test
    void shouldLeaveContentWithoutRelativeDatesUntouched() {
        MemoryTemporalNormalizer normalizer = new MemoryTemporalNormalizer(new KubeOnCallProperties());
        MemoryEntry entry = entry(
                "2026-07-09 payment-service OOM", Instant.parse("2026-07-10T01:00:00Z"), Map.of("source", "alarm"));

        assertSame(entry, normalizer.normalize(entry, Instant.parse("2026-07-10T02:00:00Z")));
        assertTrue(entry.metadata().containsKey("source"));
    }

    private static MemoryEntry entry(String content, Instant createdAt, Map<String, String> metadata) {
        return new MemoryEntry(
                "memory-date",
                MemoryType.INCIDENT_SUMMARY,
                MemoryScope.SERVICE,
                "payment incident",
                content,
                "payment-service",
                null,
                null,
                createdAt,
                createdAt,
                metadata);
    }
}
