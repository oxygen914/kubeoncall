package com.kubeoncall.alarm.inbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.ingest.InboundAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

/** Redis Stream based inbox. A delivery key is claimed before a 202 response is returned. */
@Service
public class RedisAlarmEventInbox implements AlarmEventInbox {

    private static final String DELIVERY_KEY_PREFIX = "alarm-delivery:";
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>(
            "local claimed = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2])\n"
                    + "if not claimed then return 0 end\n"
                    + "local write = redis.pcall('XADD', KEYS[2], '*', 'event', ARGV[3])\n"
                    + "if type(write) == 'table' and write.err then\n"
                    + "  redis.call('DEL', KEYS[1])\n"
                    + "  return redis.error_reply(write.err)\n"
                    + "end\n"
                    + "return 1",
            Long.class);
    private static final DefaultRedisScript<Long> RETRY_SCRIPT = new DefaultRedisScript<>(
            "local pending = redis.call('XPENDING', KEYS[1], ARGV[2], ARGV[3], ARGV[3], 1)\n"
                    + "if #pending == 0 or pending[1][2] ~= ARGV[4] then return 0 end\n"
                    + "redis.call('XADD', KEYS[1], '*', 'event', ARGV[1])\n"
                    + "return redis.call('XACK', KEYS[1], ARGV[2], ARGV[3])",
            Long.class);
    private static final DefaultRedisScript<Long> DEAD_LETTER_SCRIPT = new DefaultRedisScript<>(
            "local pending = redis.call('XPENDING', KEYS[1], ARGV[3], ARGV[4], ARGV[4], 1)\n"
                    + "if #pending == 0 or pending[1][2] ~= ARGV[5] then return 0 end\n"
                    + "redis.call('XADD', KEYS[2], '*', 'event', ARGV[1], 'reason', ARGV[2])\n"
                    + "return redis.call('XACK', KEYS[1], ARGV[3], ARGV[4])",
            Long.class);
    private static final DefaultRedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>(
            "local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)\n"
                    + "if #pending == 0 or pending[1][2] ~= ARGV[3] then return 0 end\n"
                    + "return redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)\n"
                    + "if #pending == 0 or pending[1][2] ~= ARGV[3] then return 0 end\n"
                    + "local claimed = redis.call('XCLAIM', KEYS[1], ARGV[1], ARGV[3], 0, ARGV[2], 'JUSTID')\n"
                    + "if #claimed == 0 then return 0 end\n"
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public RedisAlarmEventInbox(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public EnqueueResult enqueue(InboundAlarmEvent event) {
        try {
            Long accepted = redisTemplate.execute(
                    ENQUEUE_SCRIPT,
                    List.of(
                            deliveryKey(event.deliveryKey()),
                            properties.getAlarm().getInboxStreamKey()),
                    "accepted",
                    String.valueOf(retention().toSeconds()),
                    objectMapper.writeValueAsString(event));
            if (!Long.valueOf(1).equals(accepted)) {
                return new EnqueueResult(false, true, event.eventId());
            }
            ensureConsumerGroup();
            return new EnqueueResult(true, false, event.eventId());
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Alarm inbox is unavailable", ex);
        }
    }

    @Override
    public List<ClaimedAlarmEvent> claim(String consumer, int batchSize, Duration block) {
        try {
            ensureConsumerGroup();
            List<MapRecord<String, String, String>> reclaimed = reclaimPending(consumer, batchSize);
            if (!reclaimed.isEmpty()) {
                return reclaimed.stream()
                        .map(record -> toClaimed(record, consumer))
                        .toList();
            }
            List<MapRecord<String, String, String>> records = streamOperations()
                    .read(
                            Consumer.from(properties.getAlarm().getInboxConsumerGroup(), consumer),
                            StreamReadOptions.empty()
                                    .count(Math.max(1, batchSize))
                                    .block(block),
                            StreamOffset.create(properties.getAlarm().getInboxStreamKey(), ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            return records.stream().map(record -> toClaimed(record, consumer)).toList();
        } catch (RuntimeException ex) {
            throw new AlarmInboxUnavailableException("Unable to claim alarm inbox events", ex);
        }
    }

    @Override
    public boolean renew(ClaimedAlarmEvent event) {
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT,
                    List.of(properties.getAlarm().getInboxStreamKey()),
                    properties.getAlarm().getInboxConsumerGroup(),
                    event.recordId(),
                    event.consumer());
            return Long.valueOf(1).equals(renewed);
        } catch (RuntimeException ex) {
            throw new AlarmInboxUnavailableException("Unable to renew alarm inbox claim", ex);
        }
    }

    @Override
    public void acknowledge(ClaimedAlarmEvent event) {
        try {
            Long acknowledged = redisTemplate.execute(
                    ACK_SCRIPT,
                    List.of(properties.getAlarm().getInboxStreamKey()),
                    properties.getAlarm().getInboxConsumerGroup(),
                    event.recordId(),
                    event.consumer());
            requireAcknowledged(acknowledged, "acknowledge");
        } catch (RuntimeException ex) {
            throw new AlarmInboxUnavailableException("Unable to acknowledge alarm inbox event", ex);
        }
    }

    @Override
    public void retry(ClaimedAlarmEvent event, String reason) {
        try {
            Long acknowledged = redisTemplate.execute(
                    RETRY_SCRIPT,
                    List.of(properties.getAlarm().getInboxStreamKey()),
                    objectMapper.writeValueAsString(event.event().nextAttempt()),
                    properties.getAlarm().getInboxConsumerGroup(),
                    event.recordId(),
                    event.consumer());
            requireAcknowledged(acknowledged, "retry");
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Unable to retry alarm inbox event", ex);
        }
    }

    @Override
    public void deadLetter(ClaimedAlarmEvent event, String reason) {
        try {
            Long acknowledged = redisTemplate.execute(
                    DEAD_LETTER_SCRIPT,
                    List.of(
                            properties.getAlarm().getInboxStreamKey(),
                            properties.getAlarm().getInboxDeadLetterKey()),
                    objectMapper.writeValueAsString(event.event()),
                    reason == null ? "processing failed" : reason,
                    properties.getAlarm().getInboxConsumerGroup(),
                    event.recordId(),
                    event.consumer());
            requireAcknowledged(acknowledged, "dead-letter");
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Unable to dead-letter alarm inbox event", ex);
        }
    }

    private ClaimedAlarmEvent toClaimed(MapRecord<String, String, String> record, String consumer) {
        try {
            String payload = record.getValue().get("event");
            return new ClaimedAlarmEvent(
                    record.getId().getValue(), objectMapper.readValue(payload, InboundAlarmEvent.class), consumer);
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Unable to decode alarm inbox event", ex);
        }
    }

    private void requireAcknowledged(Long acknowledged, String operation) {
        if (!Long.valueOf(1).equals(acknowledged)) {
            throw new IllegalStateException("Alarm inbox record is no longer owned for " + operation);
        }
    }

    private void ensureConsumerGroup() {
        try {
            streamOperations()
                    .createGroup(
                            properties.getAlarm().getInboxStreamKey(),
                            ReadOffset.from("0-0"),
                            properties.getAlarm().getInboxConsumerGroup());
        } catch (RuntimeException ex) {
            if (!isBusyGroup(ex)) {
                throw ex;
            }
            // Redis reports BUSYGROUP after the first creator; no action is required.
        }
    }

    private boolean isBusyGroup(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toUpperCase(java.util.Locale.ROOT).contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<MapRecord<String, String, String>> reclaimPending(String consumer, int batchSize) {
        PendingMessages pending = streamOperations()
                .pending(
                        properties.getAlarm().getInboxStreamKey(),
                        properties.getAlarm().getInboxConsumerGroup(),
                        Range.unbounded(),
                        Math.max(1, batchSize));
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        Duration minIdle = Duration.ofMillis(Math.max(1, properties.getAlarm().getInboxPendingClaimIdleMillis()));
        List<RecordId> ids = new ArrayList<>();
        pending.forEach(message -> {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                ids.add(message.getId());
            }
        });
        if (ids.isEmpty()) {
            return List.of();
        }
        List<MapRecord<String, String, String>> claimed = streamOperations()
                .claim(
                        properties.getAlarm().getInboxStreamKey(),
                        properties.getAlarm().getInboxConsumerGroup(),
                        consumer,
                        minIdle,
                        ids.toArray(RecordId[]::new));
        return claimed == null ? List.of() : claimed;
    }

    private Duration retention() {
        return Duration.ofHours(Math.max(1, properties.getAlarm().getInboxRetentionHours()));
    }

    private String deliveryKey(String deliveryKey) {
        return DELIVERY_KEY_PREFIX + deliveryKey;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private StreamOperations<String, String, String> streamOperations() {
        return (StreamOperations) redisTemplate.opsForStream();
    }
}
