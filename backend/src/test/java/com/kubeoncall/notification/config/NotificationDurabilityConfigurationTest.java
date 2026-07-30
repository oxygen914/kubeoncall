package com.kubeoncall.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.notification.application.NotificationDispatcher;
import com.kubeoncall.notification.application.NotificationReplayService;
import com.kubeoncall.notification.application.NotificationSubmissionService;
import com.kubeoncall.notification.delivery.NotificationDeliveryOutboxHandler;
import com.kubeoncall.notification.delivery.NotificationDeliveryRepository;
import com.kubeoncall.notification.spi.NotificationRouteResolver;

class NotificationDurabilityConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationDurabilityConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(OutboxWriter.class, () -> mock(OutboxWriter.class))
            .withBean(OperationAuditWriter.class, () -> mock(OperationAuditWriter.class))
            .withBean(NotificationRouteResolver.class, () -> message -> java.util.Optional.empty())
            .withBean(NotificationDispatcher.class, () -> mock(NotificationDispatcher.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void staysDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(NotificationDeliveryRepository.class);
            assertThat(context).doesNotHaveBean(NotificationSubmissionService.class);
        });
    }

    @Test
    void requiresBothMysqlAndNotificationFlags() {
        contextRunner
                .withPropertyValues("kubeoncall.mysql-enabled=true", "kubeoncall.notifications.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationDeliveryRepository.class);
                    assertThat(context).hasSingleBean(NotificationSubmissionService.class);
                    assertThat(context).hasSingleBean(NotificationDeliveryOutboxHandler.class);
                    assertThat(context).hasSingleBean(NotificationReplayService.class);
                });
    }
}
