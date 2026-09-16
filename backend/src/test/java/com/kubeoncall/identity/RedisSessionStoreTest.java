package com.kubeoncall.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class RedisSessionStoreTest {

    @Test
    void extendsUserIndexWithoutAllowingShorterSessionToReduceItsTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(1L);

        RedisSessionStore store = newStore(redisTemplate);
        SessionRecord record = new SessionRecord(
                7L,
                "usr_7",
                "alice",
                "Alice",
                1L,
                Instant.parse("2026-09-16T00:00:00Z"),
                Instant.parse("2026-09-16T00:00:00Z"),
                Instant.parse("2026-09-16T01:00:00Z"),
                "csrf-secret");

        store.create(record, Duration.ofHours(1));
        store.create(record, Duration.ofMinutes(5));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(setOperations, org.mockito.Mockito.times(2)).add(keyCaptor.capture(), sessionCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(keyCaptor.getAllValues()).containsOnly("koc:session:user:7");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, org.mockito.Mockito.times(2))
                .execute(scriptCaptor.capture(), keysCaptor.capture(), argsCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(scriptCaptor.getAllValues())
                .allSatisfy(script -> org.assertj.core.api.Assertions.assertThat(script.getScriptAsString())
                        .contains("current == -1 or current >= requested")
                        .contains("redis.call('EXPIRE', KEYS[1], requested)"));
        org.assertj.core.api.Assertions.assertThat(keysCaptor.getAllValues())
                .containsOnly(List.of("koc:session:user:7"));
        org.assertj.core.api.Assertions.assertThat(argsCaptor.getAllValues())
                .extracting(args -> String.valueOf(args[0]))
                .containsExactly("3600", "300");
    }

    private static RedisSessionStore newStore(StringRedisTemplate redisTemplate) {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new RedisSessionStore(redisTemplate, objectMapper, true);
    }
}
