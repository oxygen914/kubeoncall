package com.kubeoncall.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.state.AlarmAcknowledgementStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmAcknowledgementStoreTest {

    @Test
    void shouldPersistAcknowledgementAndClearPreviousEscalation() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AlarmAcknowledgementStore store = new AlarmAcknowledgementStore(redisTemplate, new ObjectMapper());

        AlarmAcknowledgementStore.AlarmAcknowledgement written = store.acknowledge(
                "fp-ack",
                "oncall-user",
                "investigating",
                Duration.ofSeconds(900)
        );

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("alarm-ack:fp-ack"), rawCaptor.capture(), eq(Duration.ofSeconds(900)));
        verify(redisTemplate).delete("alarm-escalation:fp-ack");
        when(valueOperations.get("alarm-ack:fp-ack")).thenReturn(rawCaptor.getValue());

        Optional<AlarmAcknowledgementStore.AlarmAcknowledgement> found = store.find("fp-ack");

        assertTrue(found.isPresent());
        assertEquals("oncall-user", found.get().acknowledgedBy());
        assertEquals("investigating", found.get().reason());
        assertEquals(written.expiresAt(), found.get().expiresAt());
    }
}
