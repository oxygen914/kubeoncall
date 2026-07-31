package com.kubeoncall.realtime;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.outbox.OutboxEventHandler;

@Configuration(proxyBeanMethods = false)
public class RealtimeConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "kubeoncall.realtime", name = "cluster-transport", havingValue = "redis")
    public RedisMessageListenerContainer realtimeMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    public RealtimeEventBroker realtimeEventBroker(
            ObjectProvider<Clock> clockProvider,
            @Value("${kubeoncall.realtime.replay-capacity:2048}") int replayCapacity,
            @Value("${kubeoncall.realtime.replay-retention-seconds:600}") long retentionSeconds) {
        Clock clock = clockProvider.getIfAvailable();
        return new InMemoryRealtimeEventBroker(
                replayCapacity, Duration.ofSeconds(retentionSeconds), clock == null ? Clock.systemUTC() : clock);
    }

    @Bean
    public RealtimeEventHub realtimeEventHub(
            RealtimeEventBroker broker, ObjectProvider<RealtimeClusterTransport> transportProvider) {
        return new RealtimeEventHub(broker, transportProvider);
    }

    @Bean
    public OutboxEventHandler alarmAcknowledgedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("alarm.acknowledged", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler alarmRecoveryConfirmedRealtimeHandler(
            ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("alarm.recovery.confirmed", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler alarmSilenceApprovedRealtimeHandler(
            ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("alarm.silence.approved", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler approvalDecidedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("approval.decided", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler approvalRequestedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("approval.requested", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler executionCreatedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("execution.created", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler executionUpdatedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("execution.updated", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler sandboxRunCreatedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("sandbox.run.created", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler sandboxRunCancelledRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("sandbox.run.cancelled", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler taskCreatedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("task.created", objectMapper, eventHub);
    }

    @Bean
    public OutboxEventHandler taskUpdatedRealtimeHandler(ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return handler("task.updated", objectMapper, eventHub);
    }

    private static OutboxEventHandler handler(String eventType, ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        return new RealtimeOutboxEventHandler(eventType, objectMapper, eventHub);
    }
}
