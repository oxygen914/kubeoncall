package com.kubeoncall.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;

class GraphStateStoreTest {

    @Test
    void shouldAcquireAndReleaseResumeLeaseWithOwnershipToken() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("graph-state-resume-lease:exec-1"), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(true);
        GraphStateStore store = new GraphStateStore(redisTemplate, new ObjectMapper());

        String token =
                store.tryAcquireResumeLease("exec-1", Duration.ofSeconds(30)).orElseThrow();
        store.releaseResumeLease("exec-1", token);

        assertFalse(token.isBlank());
        verify(redisTemplate)
                .execute(any(RedisScript.class), eq(java.util.List.of("graph-state-resume-lease:exec-1")), eq(token));
    }

    @Test
    void shouldRejectConcurrentResumeLease() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
        GraphStateStore store = new GraphStateStore(redisTemplate, new ObjectMapper());

        assertTrue(store.tryAcquireResumeLease("exec-1", Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void shouldRenewResumeLeaseOnlyForItsOwnershipToken() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), any(), any(Object[].class)))
                .thenReturn(1L);
        GraphStateStore store = new GraphStateStore(redisTemplate, new ObjectMapper());

        assertTrue(store.renewResumeLease("exec-1", "lease-1", Duration.ofSeconds(30)));

        verify(redisTemplate)
                .execute(
                        any(RedisScript.class),
                        eq(java.util.List.of("graph-state-resume-lease:exec-1")),
                        eq("lease-1"),
                        eq("30000"));
    }
}
