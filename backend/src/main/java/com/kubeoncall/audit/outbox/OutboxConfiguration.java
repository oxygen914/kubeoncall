package com.kubeoncall.audit.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class OutboxConfiguration {

    @Bean
    public OutboxRepository outboxRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new OutboxRepository(jdbcTemplate, transactionManager);
    }

    @Bean
    public OutboxEventHandlerRegistry outboxEventHandlerRegistry(ObjectProvider<OutboxEventHandler> handlerProvider) {
        return new OutboxEventHandlerRegistry(handlerProvider.orderedStream().toList());
    }

    @Bean
    public OutboxWorker outboxWorker(
            OutboxRepository repository,
            OutboxEventHandlerRegistry registry,
            ObjectProvider<Clock> clockProvider,
            @Value("${kubeoncall.outbox.lease-seconds:30}") long leaseSeconds,
            @Value("${kubeoncall.outbox.initial-backoff-seconds:1}") long initialBackoffSeconds,
            @Value("${kubeoncall.outbox.max-backoff-seconds:300}") long maxBackoffSeconds,
            @Value("${kubeoncall.outbox.batch-size:20}") int batchSize) {
        Clock clock = clockProvider.getIfAvailable();
        if (clock == null) {
            clock = Clock.systemUTC();
        }
        return new OutboxWorker(
                repository,
                registry,
                clock,
                newOwnerToken(),
                Duration.ofSeconds(leaseSeconds),
                Duration.ofSeconds(initialBackoffSeconds),
                Duration.ofSeconds(maxBackoffSeconds),
                batchSize);
    }

    /**
     * A token shared by two live instances would let an old worker pass another instance's owner
     * fence after reclaim. A random token per worker bean makes ownership unique for every process
     * instance; event-level idempotency still protects a side effect completed before lease loss.
     */
    private static String newOwnerToken() {
        return "outbox-" + UUID.randomUUID();
    }
}
