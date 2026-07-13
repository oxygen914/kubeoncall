package com.kubeoncall.alarm.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import com.fasterxml.jackson.databind.ObjectMapper;

class AlarmMaintenanceWindowStoreTest {

    @Test
    void shouldSaveAndLoadActiveWindowFromExpiryIndex() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zset);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AlarmMaintenanceWindowStore store = new AlarmMaintenanceWindowStore(redis, mapper);
        Instant now = Instant.now();
        AlarmMaintenanceWindow window = new AlarmMaintenanceWindow(
                "mw-1",
                now.minusSeconds(30),
                now.plusSeconds(600),
                Map.of("service", "payment-*"),
                "release",
                "operator-a",
                "approver-b",
                "change-123",
                now.minusSeconds(60));
        String json = mapper.writeValueAsString(window);

        store.save(window);

        verify(values).set(eq("alarm-maintenance-window:mw-1"), eq(json), any(Duration.class));
        verify(zset)
                .add("alarm-maintenance-window:active", "mw-1", window.endsAt().toEpochMilli());

        when(zset.rangeByScore(eq("alarm-maintenance-window:active"), anyDouble(), eq(Double.POSITIVE_INFINITY)))
                .thenReturn(Set.of("mw-1"));
        when(values.get("alarm-maintenance-window:mw-1")).thenReturn(json);

        var active = store.activeAt(now);
        assertEquals(1, active.size());
        assertEquals("mw-1", active.get(0).id());
        verify(zset).removeRangeByScore("alarm-maintenance-window:active", 0, now.toEpochMilli());
    }

    @Test
    void shouldRemoveIndexAndPayloadOnRevoke() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.delete("alarm-maintenance-window:mw-1")).thenReturn(true);
        AlarmMaintenanceWindowStore store =
                new AlarmMaintenanceWindowStore(redis, new ObjectMapper().findAndRegisterModules());

        assertTrue(store.delete("mw-1"));
        verify(zset).remove("alarm-maintenance-window:active", "mw-1");
    }
}
