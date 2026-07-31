package com.kubeoncall.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.notification.delivery.NotificationDeliveryRecord;
import com.kubeoncall.notification.delivery.NotificationDeliveryRepository;
import com.kubeoncall.notification.delivery.NotificationDeliveryStatus;

/** Audited operator replay for terminal failed notification deliveries. */
public class NotificationReplayService {

    private final NotificationDeliveryRepository repository;
    private final OutboxWriter outboxWriter;
    private final OperationAuditWriter auditWriter;
    private final Clock clock;

    public NotificationReplayService(
            NotificationDeliveryRepository repository,
            OutboxWriter outboxWriter,
            OperationAuditWriter auditWriter,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public ReplayResult replay(ReplayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String deliveryId = requireText(command.deliveryId(), "deliveryId");
        String reason = requireText(command.reason(), "reason");
        String requestId = NotificationDeliveryIds.requestId(command.requestId());
        NotificationDeliveryRecord delivery;
        try {
            delivery = repository.findByPublicId(deliveryId);
        } catch (IllegalArgumentException exception) {
            throw new ReplayException(Code.NOT_FOUND, "Notification delivery does not exist");
        }
        if (delivery.status() != NotificationDeliveryStatus.FAILED
                && delivery.status() != NotificationDeliveryStatus.DEAD_LETTER) {
            throw new ReplayException(Code.CONFLICT, "Only failed notification deliveries can be replayed");
        }

        Instant now = clock.instant();
        if (!repository.requeue(delivery.publicId(), delivery.status(), now)) {
            throw new ReplayException(Code.CONFLICT, "Notification delivery state changed before replay");
        }
        enqueue(delivery, requestId);
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorId(), command.actorDisplayName())
                .action("notification.delivery.replay")
                .resource("notification_delivery", delivery.publicId())
                .result("SUCCESS")
                .reason(reason)
                .before(Map.of(
                        "status", delivery.status().name(),
                        "attempt", delivery.attempt(),
                        "replayCount", delivery.replayCount()))
                .after(Map.of(
                        "status",
                        NotificationDeliveryStatus.PENDING.name(),
                        "attempt",
                        0,
                        "replayCount",
                        delivery.replayCount() + 1))
                .requestId(requestId)
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
        return new ReplayResult(delivery.publicId(), NotificationDeliveryStatus.PENDING, delivery.replayCount() + 1);
    }

    private void enqueue(NotificationDeliveryRecord delivery, String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveryId", delivery.publicId());
        payload.put("deliveryKey", delivery.deliveryKey());
        payload.put("providerKey", delivery.destination().providerKey());
        payload.put("destinationId", delivery.destination().id());
        payload.put("manualReplay", true);
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "NOTIFICATION_DELIVERY",
                delivery.publicId(),
                NotificationSubmissionService.DELIVERY_REQUESTED_EVENT,
                payload,
                requestId));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record ReplayCommand(
            String deliveryId,
            Long actorId,
            String actorDisplayName,
            String reason,
            String requestId,
            String sourceIp,
            String userAgent) {}

    public record ReplayResult(String deliveryId, NotificationDeliveryStatus status, int replayCount) {}

    public enum Code {
        NOT_FOUND,
        CONFLICT
    }

    public static final class ReplayException extends RuntimeException {

        private final Code code;

        ReplayException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}
