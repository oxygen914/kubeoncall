package com.kubeoncall.notification.spi;

import java.util.Optional;

import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationRoute;

/** Resolves a logical routing key without exposing provider credentials to the message producer. */
public interface NotificationRouteResolver {

    Optional<NotificationRoute> resolve(NotificationMessage message);
}
