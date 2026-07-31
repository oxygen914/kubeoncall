package com.kubeoncall.alarm.correlation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Durable change-event timeline with a local fallback when Redis is unavailable. */
@Repository
@Primary
public class RedisChangeEventRepository implements ChangeEventRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChangeEventRepository.class);
    /** Redis ZSET holding the serialized change-event timeline; the backfill source. */
    public static final String TIMELINE_KEY = "alarm-change-events:timeline";

    private static final String IDEMPOTENCY_PREFIX = "alarm-change-event:id:";
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final DefaultRedisScript<Long> SAVE_IF_ABSENT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 0
            end
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final InMemoryChangeEventRepository fallback;

    public RedisChangeEventRepository(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, InMemoryChangeEventRepository fallback) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public void save(ChangeEvent event) {
        saveIfAbsent(event);
    }

    @Override
    public boolean saveIfAbsent(ChangeEvent event) {
        if (event == null) {
            return false;
        }
        try {
            String serialized = objectMapper.writeValueAsString(event);
            Long accepted = redisTemplate.execute(
                    SAVE_IF_ABSENT_SCRIPT,
                    List.of(IDEMPOTENCY_PREFIX + event.changeId(), TIMELINE_KEY),
                    String.valueOf(RETENTION.toMillis()),
                    String.valueOf(event.changedAt().toEpochMilli()),
                    serialized,
                    String.valueOf(Instant.now().minus(RETENTION).toEpochMilli()));
            if (accepted == null || accepted == 0) {
                return false;
            }
            fallback.saveIfAbsent(event);
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to persist change event; local timeline remains available: errorType={}",
                    ex.getClass().getSimpleName());
            return fallback.saveIfAbsent(event);
        } catch (Exception ex) {
            log.warn(
                    "Unable to serialize change event: errorType={}",
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public List<ChangeEvent> findBetween(Instant from, Instant to, String cluster, String namespace) {
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? Instant.now() : to;
        try {
            java.util.Set<String> raw =
                    redisTemplate.opsForZSet().rangeByScore(TIMELINE_KEY, start.toEpochMilli(), end.toEpochMilli());
            if (raw == null || raw.isEmpty()) {
                return fallback.findBetween(start, end, cluster, namespace);
            }
            List<ChangeEvent> events = new ArrayList<>();
            for (String item : raw) {
                try {
                    ChangeEvent event = objectMapper.readValue(item, ChangeEvent.class);
                    if (matches(cluster, event.cluster()) && matches(namespace, event.namespace())) {
                        events.add(event);
                    }
                } catch (Exception ex) {
                    log.warn(
                            "Ignoring malformed persisted change event: errorType={}",
                            ex.getClass().getSimpleName());
                }
            }
            return events.stream()
                    .sorted(Comparator.comparing(ChangeEvent::changedAt).reversed())
                    .toList();
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to query persisted change events; using local timeline: errorType={}",
                    ex.getClass().getSimpleName());
            return fallback.findBetween(start, end, cluster, namespace);
        }
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || actual == null || actual.isBlank() || expected.equals(actual);
    }
}
