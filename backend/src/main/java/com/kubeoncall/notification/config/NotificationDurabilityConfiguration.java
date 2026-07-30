package com.kubeoncall.notification.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Enables durable notification submission only when both MySQL and notifications are enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "kubeoncall",
        name = {"mysql-enabled", "notifications.enabled"},
        havingValue = "true")
public class NotificationDurabilityConfiguration {

    @Bean
    public NotificationDeliveryRepository notificationDeliveryRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new NotificationDeliveryRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    public NotificationSubmissionService notificationSubmissionService(
            NotificationRouteResolver routeResolver,
            NotificationDeliveryRepository repository,
            OutboxWriter outboxWriter,
            ObjectProvider<Clock> clockProvider) {
        return new NotificationSubmissionService(routeResolver, repository, outboxWriter, clock(clockProvider));
    }

    @Bean
    public NotificationDeliveryOutboxHandler notificationDeliveryOutboxHandler(
            NotificationDeliveryRepository repository,
            NotificationDispatcher dispatcher,
            ObjectProvider<Clock> clockProvider,
            ObjectProvider<KubeOnCallMetricsService> metricsServiceProvider) {
        return new NotificationDeliveryOutboxHandler(
                repository, dispatcher, clock(clockProvider), metricsServiceProvider.getIfAvailable());
    }

    @Bean
    public NotificationReplayService notificationReplayService(
            NotificationDeliveryRepository repository,
            OutboxWriter outboxWriter,
            OperationAuditWriter auditWriter,
            ObjectProvider<Clock> clockProvider) {
        return new NotificationReplayService(repository, outboxWriter, auditWriter, clock(clockProvider));
    }

    private static Clock clock(ObjectProvider<Clock> provider) {
        Clock configured = provider.getIfAvailable();
        return configured == null ? Clock.systemUTC() : configured;
    }
}
