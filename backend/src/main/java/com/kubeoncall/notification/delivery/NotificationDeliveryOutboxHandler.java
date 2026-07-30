package com.kubeoncall.notification.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.kubeoncall.audit.outbox.OutboxEvent;
import com.kubeoncall.audit.outbox.OutboxEventHandler;
import com.kubeoncall.notification.application.NotificationDeliveryRequest;
import com.kubeoncall.notification.application.NotificationDeliveryResult;
import com.kubeoncall.notification.application.NotificationDispatcher;
import com.kubeoncall.notification.application.NotificationSubmissionService;
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Outbox consumer that retries one physical destination without replaying successful siblings. */
public final class NotificationDeliveryOutboxHandler implements OutboxEventHandler {

    private final NotificationDeliveryRepository repository;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;
    private final KubeOnCallMetricsService metricsService;

    public NotificationDeliveryOutboxHandler(
            NotificationDeliveryRepository repository, NotificationDispatcher dispatcher, Clock clock) {
        this(repository, dispatcher, clock, null);
    }

    public NotificationDeliveryOutboxHandler(
            NotificationDeliveryRepository repository,
            NotificationDispatcher dispatcher,
            Clock clock,
            KubeOnCallMetricsService metricsService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metricsService = metricsService;
    }

    @Override
    public String eventType() {
        return NotificationSubmissionService.DELIVERY_REQUESTED_EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        NotificationDeliveryRecord delivery = repository.findByPublicId(event.aggregatePublicId());
        if (delivery.status().terminal()) {
            return;
        }

        Instant now = clock.instant();
        repository.markProcessing(delivery.publicId(), event.attempt(), now);
        NotificationDeliveryRequest request =
                new NotificationDeliveryRequest(delivery.publicId(), delivery.message(), delivery.destination());
        NotificationDeliveryResult result = dispatcher.deliver(request);
        if (result.delivered()) {
            repository.markDelivered(delivery.publicId(), event.attempt(), result, clock.instant());
            record(delivery, NotificationDeliveryStatus.DELIVERED, false);
            return;
        }

        if (!result.retryable()) {
            repository.markFailed(
                    delivery.publicId(), event.attempt(), result, NotificationDeliveryStatus.FAILED, clock.instant());
            record(delivery, NotificationDeliveryStatus.FAILED, false);
            return;
        }

        NotificationDeliveryStatus failureStatus = event.attempt() >= event.maxAttempts()
                ? NotificationDeliveryStatus.DEAD_LETTER
                : NotificationDeliveryStatus.RETRYING;
        repository.markFailed(delivery.publicId(), event.attempt(), result, failureStatus, clock.instant());
        record(delivery, failureStatus, true);
        throw new NotificationRetryableException(result.code(), result.detail());
    }

    private void record(NotificationDeliveryRecord delivery, NotificationDeliveryStatus status, boolean retryable) {
        if (metricsService != null) {
            metricsService.recordNotificationDelivery(delivery.destination().providerKey(), status.name(), retryable);
        }
    }

    static final class NotificationRetryableException extends RuntimeException {

        private final String code;

        NotificationRetryableException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
