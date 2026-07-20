package com.kubeoncall.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;

class AlarmSilenceApprovalStoreTest {

    @Test
    void shouldPersistAndReadSilenceApproval() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        AlarmSilenceApprovalStore store = new AlarmSilenceApprovalStore(redisTemplate, objectMapper);

        AlarmSilenceApprovalStore.SilenceApproval written =
                store.approve("fp-approval", "didi", "maintenance window", Duration.ofSeconds(600));

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations)
                .set(eq("alarm-silence-approval:fp-approval"), rawCaptor.capture(), eq(Duration.ofSeconds(600)));
        when(valueOperations.get("alarm-silence-approval:fp-approval")).thenReturn(rawCaptor.getValue());

        Optional<AlarmSilenceApprovalStore.SilenceApproval> found = store.find("fp-approval");

        assertTrue(found.isPresent());
        assertEquals("fp-approval", found.get().fingerprint());
        assertEquals("didi", found.get().approvedBy());
        assertEquals("maintenance window", found.get().reason());
        assertEquals(written.expiresAt(), found.get().expiresAt());
    }

    @Test
    void shouldIgnoreExpiredSilenceApproval() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        AlarmSilenceApprovalStore store = new AlarmSilenceApprovalStore(redisTemplate, objectMapper);
        String key = "alarm-silence-approval:fp-expired";
        String raw = objectMapper.writeValueAsString(Map.of(
                "fingerprint", "fp-expired",
                "approvedBy", "didi",
                "reason", "old approval",
                "approvedAt", Instant.now().minusSeconds(7200).toString(),
                "expiresAt", Instant.now().minusSeconds(3600).toString()));
        when(valueOperations.get(key)).thenReturn(raw);

        Optional<AlarmSilenceApprovalStore.SilenceApproval> found = store.find("fp-expired");

        assertTrue(found.isEmpty());
        verify(redisTemplate).delete(key);
    }
}
