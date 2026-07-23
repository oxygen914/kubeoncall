package com.kubeoncall.observability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.kubeoncall.service.KubeOnCallMetricsService;

/**
 * Executes a real Redis PING when Actuator health is scraped and emits bounded dependency metrics.
 * It deliberately has a distinct component name so it complements Spring Boot's built-in Redis
 * contributor instead of replacing it.
 */
@Component("kubeOnCallRedisDependency")
public class RedisDependencyHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallMetricsService metricsService;
    private final DependencyCircuitBreaker circuitBreaker;

    public RedisDependencyHealthIndicator(StringRedisTemplate redisTemplate, KubeOnCallMetricsService metricsService) {
        this(redisTemplate, metricsService, null);
    }

    @Autowired
    public RedisDependencyHealthIndicator(
            StringRedisTemplate redisTemplate,
            KubeOnCallMetricsService metricsService,
            DependencyCircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Health health() {
        long startedAt = System.nanoTime();
        try {
            String reply = circuitBreaker == null
                    ? redisTemplate.execute((RedisCallback<String>) connection -> connection.ping())
                    : circuitBreaker.execute(
                            "redis",
                            () -> redisTemplate.execute((RedisCallback<String>) connection -> connection.ping()));
            if (!"PONG".equalsIgnoreCase(reply)) {
                record("error", startedAt);
                return Health.down()
                        .withDetail("reply", reply == null ? "empty" : reply)
                        .build();
            }
            record("success", startedAt);
            return Health.up().build();
        } catch (RuntimeException ex) {
            record("error", startedAt);
            return Health.down()
                    .withDetail("errorType", ex.getClass().getSimpleName())
                    .build();
        }
    }

    private void record(String outcome, long startedAt) {
        metricsService.recordDependency(
                "redis",
                "ping",
                outcome,
                java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }
}
