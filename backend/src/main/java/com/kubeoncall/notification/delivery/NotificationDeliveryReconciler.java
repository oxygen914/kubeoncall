package com.kubeoncall.notification.delivery;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically mirrors terminal Outbox exhaustion into the notification delivery ledger. */
@Component
@ConditionalOnProperty(
        prefix = "kubeoncall",
        name = {"mysql-enabled", "notifications.enabled"},
        havingValue = "true")
public class NotificationDeliveryReconciler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryReconciler.class);

    private final NotificationDeliveryRepository repository;
    private final Clock clock;

    public NotificationDeliveryReconciler(
            NotificationDeliveryRepository repository, org.springframework.beans.factory.ObjectProvider<Clock> clocks) {
        this.repository = repository;
        Clock configured = clocks.getIfAvailable();
        this.clock = configured == null ? Clock.systemUTC() : configured;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.notifications.reconcile-poll-millis:5000}")
    public void reconcile() {
        try {
            int reconciled = repository.reconcileDeadLetters(clock.instant());
            if (reconciled > 0) {
                log.warn("Reconciled {} notification deliveries from terminal Outbox state", reconciled);
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Notification delivery reconciliation failed: errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
