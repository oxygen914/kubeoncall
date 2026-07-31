package com.kubeoncall.migration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Inventories Redis keys via SCAN (never KEYS) and classifies them by the WBS-11 migration taxonomy:
 * which keys are active business facts worth backfilling, which are short-lived session/lock/lease
 * state to let expire, and which are rebuildable caches. Output is a structured report operators use
 * to decide the backfill order and to confirm nothing with持久 value is silently skipped.
 *
 * <p>The scan is bounded by {@code backfill-scan-limit} so a runaway keyspace cannot stall the tool,
 * and each cursor page is a small batch so the scan stays non-blocking on a live Redis.
 */
@Service
public class RedisInventoryService {

    private static final Logger log = LoggerFactory.getLogger(RedisInventoryService.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final KubeOnCallProperties properties;

    public RedisInventoryService(ObjectProvider<StringRedisTemplate> redisProvider, KubeOnCallProperties properties) {
        this.redisProvider = redisProvider;
        this.properties = properties;
    }

    public InventoryReport inventory() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return new InventoryReport(0, Map.of(), Map.of(), Map.of(), "redis unavailable");
        }
        Map<String, AtomicLong> byCategory = new TreeMap<>();
        Map<String, AtomicLong> byPrefix = new TreeMap<>();
        // P0-8 TTL visibility: track how many keys expire soon so operators can decide whether a
        // backfill is even worth running (keys with <60s TTL will vanish before the cutover).
        Map<String, Long> ttlBuckets = new LinkedHashMap<>();
        long limit = Math.max(1, properties.getDataMigration().getBackfillScanLimit());
        long count = 0;
        ScanOptions options = ScanOptions.scanOptions()
                .count(properties.getDataMigration().getBackfillBatchSize())
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext() && count < limit) {
                String key = cursor.next();
                count++;
                String category = classify(key);
                byCategory.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
                byPrefix.computeIfAbsent(prefixOf(key), k -> new AtomicLong()).incrementAndGet();
                String ttlBucket = ttlBucketOf(redis.getExpire(key));
                ttlBuckets.merge(ttlBucket, 1L, Long::sum);
            }
        } catch (Exception ex) {
            log.warn("Redis inventory scan failed at count={}: {}", count, ex.getMessage());
            return new InventoryReport(
                    count,
                    toLongMap(byCategory),
                    toLongMap(byPrefix),
                    ttlBuckets,
                    "scan interrupted: " + ex.getMessage());
        }
        String note = count >= limit ? "scan limit reached; truncated" : "complete";
        return new InventoryReport(count, toLongMap(byCategory), toLongMap(byPrefix), ttlBuckets, note);
    }

    /** Bucket the Redis TTL (seconds) into ranges that drive migration decisions. -1 = no expiry. */
    private static String ttlBucketOf(long ttlSeconds) {
        if (ttlSeconds < 0) {
            return "no-expiry";
        }
        if (ttlSeconds < 60) {
            return "expiring-<60s";
        }
        if (ttlSeconds < 3600) {
            return "expiring-<1h";
        }
        if (ttlSeconds < 86400) {
            return "expiring-<1d";
        }
        return "expiring->=1d";
    }

    /** WBS-11 §2 taxonomy. Categories drive the backfill order and what is intentionally skipped. */
    static String classify(String key) {
        if (key == null) {
            return "unknown";
        }
        if (key.startsWith("alarm-active:")) {
            return "active-business-fact";
        }
        if (key.startsWith("alarm-ack:")
                || key.startsWith("alarm-silence-approval:")
                || key.startsWith("alarm-recovery:")) {
            return "active-business-fact";
        }
        if (key.startsWith("approval-request:")) {
            return "active-business-fact";
        }
        if (key.startsWith("execution-audit:")) {
            return "historical-audit";
        }
        if (key.startsWith("alarm-change-event:id:")) {
            return "historical-audit";
        }
        if (key.startsWith("skill-reload:") || key.startsWith("skill:disabled:")) {
            return "skill-state";
        }
        if (key.startsWith("koc:session:") || key.startsWith("ask-session:") || key.startsWith("graph-state:")) {
            return "session-graphstate";
        }
        if (key.startsWith("alarm-dedup:")
                || key.startsWith("alarm-delivery:")
                || key.startsWith("alarm-lifecycle")
                || key.startsWith("graph-state-resume-lease:")) {
            return "short-term-dedup-lock";
        }
        if (key.startsWith("alarm-suppression:")
                || key.startsWith("alarm-maintenance-window:")
                || key.startsWith("alarm-aggregate:")) {
            return "rebuildable-cache";
        }
        if (key.startsWith("memory-extraction:status:") || key.startsWith("tool:") || key.startsWith("koc-csrf:")) {
            return "rebuildable-cache";
        }
        return "unclassified";
    }

    private static String prefixOf(String key) {
        if (key == null) {
            return "?";
        }
        int colon = key.indexOf(':');
        if (colon < 0) {
            return key;
        }
        return key.substring(0, colon + 1);
    }

    private static Map<String, Long> toLongMap(Map<String, AtomicLong> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public record InventoryReport(
            long scanned,
            Map<String, Long> byCategory,
            Map<String, Long> byPrefix,
            Map<String, Long> byTtlBucket,
            String note) {

        public List<String> activeBusinessFactPrefixes() {
            return List.of(
                    "alarm-active:", "alarm-ack:", "alarm-silence-approval:", "alarm-recovery:", "approval-request:");
        }
    }
}
