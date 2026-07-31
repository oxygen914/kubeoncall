package com.kubeoncall.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DependencyCircuitBreakerTest {

    @Test
    void opensAfterThresholdRejectsCallsAndClosesAfterSuccessfulProbe() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDependencyCircuitBreaker().setFailureThreshold(2);
        properties.getDependencyCircuitBreaker().setResetTimeoutSeconds(10);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-23T00:00:00Z"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DependencyCircuitBreaker breaker = new DependencyCircuitBreaker(properties, metrics(registry), clock);

        assertThatThrownBy(() -> breaker.execute("MinIO", () -> {
                    throw new IllegalStateException("unavailable");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.execute("MinIO", () -> {
                    throw new IllegalStateException("unavailable");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.execute("MinIO", () -> "not-called"))
                .isInstanceOf(DependencyCircuitBreaker.CircuitOpenException.class);

        clock.advanceSeconds(10);
        assertThat(breaker.execute("MinIO", () -> "recovered")).isEqualTo("recovered");
        assertThat(registry.get("kubeoncall.dependency.circuit")
                        .tags("dependency", "minio", "event", "opened")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("kubeoncall.dependency.circuit")
                        .tags("dependency", "minio", "event", "rejected")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("kubeoncall.dependency.circuit")
                        .tags("dependency", "minio", "event", "closed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @SuppressWarnings("unchecked")
    private static KubeOnCallMetricsService metrics(SimpleMeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(registry);
        return new KubeOnCallMetricsService(provider);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
