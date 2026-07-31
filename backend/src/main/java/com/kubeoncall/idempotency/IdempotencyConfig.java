package com.kubeoncall.idempotency;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wires {@link IdempotencyService} with a configurable record TTL. The TTL bounds how long a
 * replayed response is honored; after it expires a repeat of the same key is treated as a fresh
 * request rather than a replay, so the table cannot grow unbounded.
 */
@Configuration
public class IdempotencyConfig {

    @Bean
    public IdempotencyService idempotencyService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper,
            @Value("${kubeoncall.idempotency.record-ttl-seconds:86400}") long ttlSeconds) {
        return new IdempotencyService(jdbcTemplateProvider, objectMapper, Duration.ofSeconds(ttlSeconds));
    }
}
