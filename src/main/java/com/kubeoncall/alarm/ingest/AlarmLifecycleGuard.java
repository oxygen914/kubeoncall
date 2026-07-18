package com.kubeoncall.alarm.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.inbox.AlarmInboxUnavailableException;
import com.kubeoncall.common.config.KubeOnCallProperties;

/** Prevents a late delivery from reopening an alarm after a newer lifecycle transition was handled. */
@Service
public class AlarmLifecycleGuard {

    private static final String KEY_PREFIX = "alarm-lifecycle:";
    private static final String RESERVATION_KEY_PREFIX = "alarm-lifecycle-reservation:";
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);
    private static final DefaultRedisScript<Long> RENEW_RESERVATION_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public AlarmLifecycleGuard(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean shouldProcess(NormalizedAlarmEvent event) {
        try {
            String raw = redisTemplate.opsForValue().get(key(event.fingerprint()));
            if (raw == null || raw.isBlank()) {
                return true;
            }
            LifecycleState state = objectMapper.readValue(raw, LifecycleState.class);
            Instant occurredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
            if (occurredAt.isAfter(state.occurredAt())) {
                return true;
            }
            if (occurredAt.isBefore(state.occurredAt())) {
                return false;
            }
            return statusRank(event.status()) > statusRank(state.status());
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Unable to read alarm lifecycle state", ex);
        }
    }

    public Optional<Reservation> reserve(NormalizedAlarmEvent event) {
        String token = UUID.randomUUID().toString();
        Duration ttl = reservationTtl();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(reservationKey(event.fingerprint()), token, ttl);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new AlarmInboxUnavailableException("Alarm lifecycle transition is already in progress");
            }
            if (!shouldProcess(event)) {
                release(new Reservation(event.fingerprint(), token, ttl));
                return Optional.empty();
            }
            return Optional.of(new Reservation(event.fingerprint(), token, ttl));
        } catch (AlarmInboxUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            release(new Reservation(event.fingerprint(), token, ttl));
            throw new AlarmInboxUnavailableException("Unable to reserve alarm lifecycle transition", ex);
        }
    }

    public boolean renew(Reservation reservation) {
        if (reservation == null) {
            return false;
        }
        Long renewed = redisTemplate.execute(
                RENEW_RESERVATION_SCRIPT,
                List.of(reservationKey(reservation.fingerprint())),
                reservation.token(),
                String.valueOf(Math.max(1L, reservation.ttl().toMillis())));
        return Long.valueOf(1).equals(renewed);
    }

    public void complete(Reservation reservation, NormalizedAlarmEvent event) {
        recordProcessed(event);
        if (!release(reservation)) {
            throw new AlarmInboxUnavailableException("Alarm lifecycle reservation is no longer owned");
        }
    }

    public boolean release(Reservation reservation) {
        if (reservation == null) {
            return false;
        }
        Long released = redisTemplate.execute(
                RELEASE_RESERVATION_SCRIPT, List.of(reservationKey(reservation.fingerprint())), reservation.token());
        return Long.valueOf(1).equals(released);
    }

    public void recordProcessed(NormalizedAlarmEvent event) {
        try {
            Instant occurredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
            LifecycleState state = new LifecycleState(event.status(), occurredAt);
            redisTemplate
                    .opsForValue()
                    .set(key(event.fingerprint()), objectMapper.writeValueAsString(state), retention());
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Unable to persist alarm lifecycle state", ex);
        }
    }

    private Duration retention() {
        return Duration.ofHours(Math.max(1, properties.getAlarm().getInboxRetentionHours()));
    }

    private Duration reservationTtl() {
        return Duration.ofMillis(Math.max(5000L, properties.getAlarm().getInboxPendingClaimIdleMillis() * 2L));
    }

    private String key(String fingerprint) {
        return KEY_PREFIX + fingerprint;
    }

    private String reservationKey(String fingerprint) {
        return RESERVATION_KEY_PREFIX + fingerprint;
    }

    private int statusRank(AlarmStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case FIRING -> 0;
            case SUPPRESSED -> 1;
            case RESOLVED -> 2;
        };
    }

    record LifecycleState(AlarmStatus status, Instant occurredAt) {}

    public record Reservation(String fingerprint, String token, Duration ttl) {}
}
