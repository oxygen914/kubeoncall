package com.kubeoncall.audit.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OutboxConfigurationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T01:30:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(Clock.class, () -> CLOCK);

    @Test
    void doesNotCreateOutboxBeansWhenMysqlIsDisabled() {
        contextRunner.withPropertyValues("kubeoncall.mysql-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxRepository.class);
            assertThat(context).doesNotHaveBean(OutboxEventHandlerRegistry.class);
            assertThat(context).doesNotHaveBean(OutboxWorker.class);
        });
    }

    @Test
    void createsOutboxBeansWithEmptyHandlerRegistryWhenMysqlIsEnabled() {
        contextRunner.withPropertyValues("kubeoncall.mysql-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OutboxRepository.class);
            assertThat(context).hasSingleBean(OutboxEventHandlerRegistry.class);
            assertThat(context).hasSingleBean(OutboxWorker.class);
            assertThat(context.getBean(OutboxEventHandlerRegistry.class).size()).isZero();
        });
    }

    @Test
    void generatesUniqueOwnerTokenForEveryWorkerInstance() {
        OutboxConfiguration configuration = new OutboxConfiguration();
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEventHandlerRegistry registry = new OutboxEventHandlerRegistry(List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
        when(clockProvider.getIfAvailable()).thenReturn(CLOCK);

        OutboxWorker first = configuration.outboxWorker(repository, registry, clockProvider, 30, 1, 300, 20);
        OutboxWorker second = configuration.outboxWorker(repository, registry, clockProvider, 30, 1, 300, 20);

        assertThat(first.ownerToken()).startsWith("outbox-");
        assertThat(second.ownerToken()).startsWith("outbox-");
        assertThat(first.ownerToken()).isNotEqualTo(second.ownerToken());
    }
}
