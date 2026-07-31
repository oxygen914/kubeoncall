package com.kubeoncall.web.api.v1.clientevents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ClientEventRateLimiterTest {

    @Test
    void shouldLimitSameFingerprintAndKeepStorageBounded() {
        MutableClock clock = new MutableClock();
        ClientEventRateLimiter limiter = new ClientEventRateLimiter(clock, 2, Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("usr_1", "WINDOW_ERROR", "/alarms")).isTrue();
        assertThat(limiter.tryAcquire("usr_1", "WINDOW_ERROR", "/alarms")).isFalse();
        assertThat(limiter.tryAcquire("usr_1", "RENDER_ERROR", "/alarms")).isTrue();
        assertThat(limiter.tryAcquire("usr_2", "WINDOW_ERROR", "/alarms")).isTrue();
        assertThat(limiter.trackedFingerprintCount()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("usr_1", "WINDOW_ERROR", "/alarms")).isTrue();
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-07-20T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
