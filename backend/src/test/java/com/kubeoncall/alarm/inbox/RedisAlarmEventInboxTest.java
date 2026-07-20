package com.kubeoncall.alarm.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.ingest.AlarmPayload;
import com.kubeoncall.alarm.ingest.InboundAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

class RedisAlarmEventInboxTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldCreateConsumerGroupFromBeginningAfterFirstEnqueue() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> streams = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streams);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        RedisAlarmEventInbox inbox =
                new RedisAlarmEventInbox(redisTemplate, new ObjectMapper().findAndRegisterModules(), properties);

        inbox.enqueue(event());

        ArgumentCaptor<ReadOffset> offset = ArgumentCaptor.forClass(ReadOffset.class);
        verify(streams)
                .createGroup(
                        org.mockito.ArgumentMatchers.eq(properties.getAlarm().getInboxStreamKey()),
                        offset.capture(),
                        org.mockito.ArgumentMatchers.eq(properties.getAlarm().getInboxConsumerGroup()));
        assertEquals("0-0", offset.getValue().getOffset());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldMoveRetryWithOneAtomicScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisAlarmEventInbox inbox = new RedisAlarmEventInbox(
                redisTemplate, new ObjectMapper().findAndRegisterModules(), new KubeOnCallProperties());

        inbox.retry(new AlarmEventInbox.ClaimedAlarmEvent("1-0", event()), "temporary");

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), anyList(), any(Object[].class));
        assertTrue(script.getValue().getScriptAsString().contains("XPENDING"));
        assertTrue(script.getValue().getScriptAsString().contains("XADD"));
        assertTrue(script.getValue().getScriptAsString().contains("XACK"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldRenewOnlyTheCurrentConsumerClaim() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisAlarmEventInbox inbox = new RedisAlarmEventInbox(
                redisTemplate, new ObjectMapper().findAndRegisterModules(), new KubeOnCallProperties());

        assertTrue(inbox.renew(new AlarmEventInbox.ClaimedAlarmEvent("1-0", event(), "worker-a")));

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), anyList(), any(Object[].class));
        assertTrue(script.getValue().getScriptAsString().contains("XPENDING"));
        assertTrue(script.getValue().getScriptAsString().contains("XCLAIM"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldRejectRetryWhenTheClaimIsAlreadyGone() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        RedisAlarmEventInbox inbox = new RedisAlarmEventInbox(
                redisTemplate, new ObjectMapper().findAndRegisterModules(), new KubeOnCallProperties());

        assertThrows(
                AlarmInboxUnavailableException.class,
                () -> inbox.retry(new AlarmEventInbox.ClaimedAlarmEvent("1-0", event()), "temporary"));
    }

    private static InboundAlarmEvent event() {
        AlarmPayload alarm = new AlarmPayload(
                "alarm-1",
                "fingerprint-1",
                "alertmanager",
                "P1",
                "node-a",
                "node down",
                Instant.parse("2026-07-17T10:00:00Z"),
                Map.of(),
                "fingerprint-1",
                "NodeDown",
                "node",
                "node-a",
                "cluster-a",
                null,
                null,
                "prometheus.up",
                null,
                null,
                null,
                null,
                Map.of("instance", "node-a:9100"),
                Map.of(),
                "runbook-node-down",
                "firing");
        return new InboundAlarmEvent("event-1", "batch-1", "delivery-1", "alertmanager", Instant.now(), 0, alarm);
    }
}
