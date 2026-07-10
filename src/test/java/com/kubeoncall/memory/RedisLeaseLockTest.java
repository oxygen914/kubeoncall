package com.kubeoncall.memory;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLeaseLockTest {

    @Test
    void shouldAcquireWithTtlAndReleaseOnlyByOwnerScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("memory-lock", "owner-1", Duration.ofMinutes(5))).thenReturn(true);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("memory-lock")), eq("owner-1")))
                .thenReturn(1L);
        RedisLeaseLock lock = new RedisLeaseLock(redisTemplate);

        assertEquals(true, lock.tryAcquire("memory-lock", "owner-1", Duration.ofMinutes(5)));
        assertEquals(true, lock.release("memory-lock", "owner-1"));

        verify(values).setIfAbsent("memory-lock", "owner-1", Duration.ofMinutes(5));
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class), eq(List.of("memory-lock")), eq("owner-1"));
    }
}
