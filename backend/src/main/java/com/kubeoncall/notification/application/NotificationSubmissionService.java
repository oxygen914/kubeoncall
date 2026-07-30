package com.kubeoncall.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.notification.delivery.NotificationDeliveryRecord;
import com.kubeoncall.notification.delivery.NotificationDeliveryRepository;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationRoute;
import com.kubeoncall.notification.spi.NotificationRouteResolver;

/** Atomically persists and enqueues one Outbox unit for every resolved physical destination. */
public class NotificationSubmissionService {

    public static final String DELIVERY_REQUESTED_EVENT = "notification.delivery.requested";

    private final NotificationRouteResolver routeResolver;
    private final NotificationDeliveryRepository repository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    public NotificationSubmissionService(
            NotificationRouteResolver routeResolver,
            NotificationDeliveryRepository repository,
            OutboxWriter outboxWriter,
            Clock clock) {
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public NotificationPublishResult submit(NotificationMessage message, String requestId) {
        Objects.requireNonNull(message, "message must not be null");
        NotificationRoute route = routeResolver.resolve(message).orElse(null);
        if (route == null) {
            return NotificationPublishResult.noRoute(message.eventId());
        }
        return submitResolved(message, route.destinations(), requestId);
    }

    /** Persists one notification for an explicitly selected, server-managed destination. */
    @Transactional
    public NotificationPublishResult submitToDestination(
            NotificationMessage message, NotificationDestination destination, String requestId) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        return submitResolved(message, List.of(destination), requestId);
    }

    private NotificationPublishResult submitResolved(
            NotificationMessage message, List<NotificationDestination> destinations, String requestId) {
        Instant now = clock.instant();
        String normalizedRequestId = NotificationDeliveryIds.requestId(requestId);
        List<String> deliveryIds = new ArrayList<>(destinations.size());
        boolean createdAny = false;
        for (NotificationDestination destination : destinations) {
            String deliveryKey = NotificationDeliveryIds.deliveryKey(message, destination);
            String publicId = NotificationDeliveryIds.publicId(deliveryKey);
            NotificationDeliveryRepository.CreateResult created =
                    repository.createIfAbsent(publicId, deliveryKey, message, destination, normalizedRequestId, now);
            NotificationDeliveryRecord record = created.record();
            deliveryIds.add(record.publicId());
            if (created.created()) {
                createdAny = true;
                enqueue(record, normalizedRequestId);
            }
        }
        NotificationPublishResult.Status status =
                createdAny ? NotificationPublishResult.Status.QUEUED : NotificationPublishResult.Status.ALREADY_QUEUED;
        return new NotificationPublishResult(status, message.eventId(), deliveryIds);
    }

    private void enqueue(NotificationDeliveryRecord delivery, String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveryId", delivery.publicId());
        payload.put("deliveryKey", delivery.deliveryKey());
        payload.put("providerKey", delivery.destination().providerKey());
        payload.put("destinationId", delivery.destination().id());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "NOTIFICATION_DELIVERY", delivery.publicId(), DELIVERY_REQUESTED_EVENT, payload, requestId));
    }
}
