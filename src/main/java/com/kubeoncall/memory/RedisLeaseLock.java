package com.kubeoncall.memory;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLeaseLock {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisLeaseLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String key, String owner, Duration ttl) {
        if (key == null || key.isBlank() || owner == null || owner.isBlank()) {
            return false;
        }
        Duration lease = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(30) : ttl;
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, owner, lease));
    }

    public boolean release(String key, String owner) {
        if (key == null || key.isBlank() || owner == null || owner.isBlank()) {
            return false;
        }
        Long released = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), owner);
        return released != null && released > 0;
    }
}
