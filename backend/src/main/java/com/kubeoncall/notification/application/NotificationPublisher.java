package com.kubeoncall.notification.application;

import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.notification.domain.NotificationMessage;

/**
 * Stable facade for callers. When durable notifications are disabled, publishing is an explicit
 * no-op instead of changing the existing delivery path.
 */
public final class NotificationPublisher {

    private final ObjectProvider<NotificationSubmissionService> submissionServiceProvider;

    public NotificationPublisher(ObjectProvider<NotificationSubmissionService> submissionServiceProvider) {
        this.submissionServiceProvider = Objects.requireNonNull(submissionServiceProvider, "submissionServiceProvider");
    }

    public NotificationPublishResult publish(NotificationMessage message, String requestId) {
        Objects.requireNonNull(message, "message must not be null");
        NotificationSubmissionService service = submissionServiceProvider.getIfAvailable();
        return service == null
                ? NotificationPublishResult.disabled(message.eventId())
                : service.submit(message, requestId);
    }
}
