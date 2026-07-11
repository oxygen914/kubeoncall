package com.kubeoncall.alarm.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmRecoveryStoreTest {

    @Test
    void shouldPersistAutomaticCandidateAndScheduleItsDueTime() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> sortedSet = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.opsForZSet()).thenReturn(sortedSet);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AlarmRecoveryStore store = new AlarmRecoveryStore(redisTemplate, objectMapper);
        Instant candidateAt = Instant.now();
        AlarmRecoveryState state = new AlarmRecoveryState(
                "fp-store", "alarm-store", AlarmSeverity.P2, "policy-2", "healthy for 5m",
                candidateAt, candidateAt.plusSeconds(300), false, AlarmRecoveryState.PENDING,
                null, false, null, null
        );

        store.savePending(state, Duration.ofHours(1));

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("alarm-recovery:fp-store"), rawCaptor.capture(), eq(Duration.ofHours(1)));
        verify(sortedSet).add("alarm-recovery:due", "fp-store", state.confirmAfter().toEpochMilli());
        when(values.get("alarm-recovery:fp-store")).thenReturn(rawCaptor.getValue());

        Optional<AlarmRecoveryState> restored = store.find("fp-store");

        assertTrue(restored.isPresent());
        assertEquals(state, restored.get());
    }
}
