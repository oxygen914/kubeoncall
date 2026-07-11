package com.kubeoncall.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kubeoncall.common.config.KubeOnCallProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SessionCallback;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSessionStoreTest {

    @Test
    void shouldAppendTurnWithConfiguredTtl() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ask-session:session-1")).thenReturn(null);
        executeCallbacks(redisTemplate);
        when(redisTemplate.exec()).thenReturn(java.util.List.of(true));

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setSessionTtlSeconds(600);
        properties.getMemory().setMaxSessionTurns(2);
        RedisSessionStore store = new RedisSessionStore(redisTemplate, objectMapper, properties);

        store.append("session-1", new SessionTurn("exec-1", "q1", "a1", "SUCCESS", Instant.now()));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("ask-session:session-1"), jsonCaptor.capture(), eq(Duration.ofSeconds(600)));
        SessionSnapshot stored = objectMapper.readValue(jsonCaptor.getValue(), SessionSnapshot.class);
        assertEquals("session-1", stored.sessionId());
        assertEquals(1, stored.turns().size());
        assertEquals("q1", stored.turns().get(0).question());
        verify(redisTemplate).watch("ask-session:session-1");
        verify(redisTemplate).multi();
        verify(redisTemplate).exec();
    }

    @Test
    void shouldLoadExistingSession() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        SessionSnapshot snapshot = new SessionSnapshot(
                "session-2",
                java.util.List.of(new SessionTurn("exec-2", "q2", "a2", "SUCCESS", Instant.now())),
                Instant.now(),
                Instant.now()
        );
        when(valueOperations.get("ask-session:session-2")).thenReturn(objectMapper.writeValueAsString(snapshot));
        RedisSessionStore store = new RedisSessionStore(redisTemplate, objectMapper, new KubeOnCallProperties());

        Optional<SessionSnapshot> loaded = store.find("session-2");

        assertTrue(loaded.isPresent());
        assertEquals("exec-2", loaded.get().turns().get(0).executionId());
    }

    @Test
    void shouldRetryWhenConcurrentTransactionAborts() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ask-session:session-race")).thenReturn(null);
        executeCallbacks(redisTemplate);
        when(redisTemplate.exec()).thenReturn(null, java.util.List.of(true));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setSessionAppendMaxRetries(3);
        RedisSessionStore store = new RedisSessionStore(redisTemplate, objectMapper, properties);

        store.append("session-race", new SessionTurn("exec-race", "q", "a", "SUCCESS", Instant.now()));

        verify(redisTemplate, times(2)).watch("ask-session:session-race");
        verify(redisTemplate, times(2)).exec();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void executeCallbacks(StringRedisTemplate redisTemplate) {
        when(redisTemplate.execute(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            return callback.execute(redisTemplate);
        });
    }
}
