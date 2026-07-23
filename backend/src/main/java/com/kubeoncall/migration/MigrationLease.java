package com.kubeoncall.migration;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Ownership-aware Redis lease primitives for migration runners.
 *
 * <p>A lease may expire while its original runner is still finishing. Releasing with a plain DEL in
 * that situation can delete the lease acquired by a newer runner, so release must compare the
 * holder token atomically.</p>
 */
final class MigrationLease {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private static final DefaultRedisScript<Long> RELEASE_IF_OWNED = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);
    private static final DefaultRedisScript<Long> RENEW_IF_OWNED = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0",
            Long.class);

    private MigrationLease() {}

    static boolean acquire(StringRedisTemplate redis, String leaseKey, String holderToken) {
        return redis != null
                && Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(leaseKey, holderToken, DEFAULT_TTL));
    }

    static boolean renewIfOwned(StringRedisTemplate redis, String leaseKey, String holderToken) {
        if (redis == null || leaseKey == null || holderToken == null) {
            return false;
        }
        try {
            Long renewed = redis.execute(
                    RENEW_IF_OWNED, List.of(leaseKey), holderToken, String.valueOf(DEFAULT_TTL.toMillis()));
            return Long.valueOf(1L).equals(renewed);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean releaseIfOwned(StringRedisTemplate redis, String leaseKey, String holderToken) {
        if (redis == null || leaseKey == null || holderToken == null) {
            return false;
        }
        try {
            Long released = redis.execute(RELEASE_IF_OWNED, List.of(leaseKey), holderToken);
            return Long.valueOf(1L).equals(released);
        } catch (RuntimeException ignored) {
            // The TTL remains the safety net when Redis is unavailable during runner teardown.
            return false;
        }
    }
}
