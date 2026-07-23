package com.kubeoncall.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.kubeoncall.service.KubeOnCallMetricsService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RedisDependencyHealthIndicatorTest {

    @Test
    void emitsSuccessMetricForRealPingCallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        org.springframework.boot.actuate.health.Health health =
                new RedisDependencyHealthIndicator(redis, metrics(registry)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("kubeoncall.dependency.requests")
                        .tags("dependency", "redis", "operation", "ping", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void emitsErrorMetricWithoutLeakingRedisException() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("redis password=secret"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        org.springframework.boot.actuate.health.Health health =
                new RedisDependencyHealthIndicator(redis, metrics(registry)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("errorType", "IllegalStateException");
        assertThat(registry.get("kubeoncall.dependency.requests")
                        .tags("dependency", "redis", "operation", "ping", "outcome", "error")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @SuppressWarnings("unchecked")
    private static KubeOnCallMetricsService metrics(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new KubeOnCallMetricsService(provider);
    }
}
