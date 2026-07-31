package com.kubeoncall.notification.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationRoute;
import com.kubeoncall.notification.spi.NotificationProvider;
import com.kubeoncall.notification.spi.NotificationProviderException;
import com.kubeoncall.notification.spi.NotificationRouteResolver;

/** Resolves one logical route and fans the message out while isolating destination failures. */
public class NotificationDispatcher {

    private final NotificationProviderRegistry providerRegistry;
    private final NotificationRouteResolver routeResolver;

    public NotificationDispatcher(
            NotificationProviderRegistry providerRegistry, NotificationRouteResolver routeResolver) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry must not be null");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver must not be null");
    }

    public NotificationDispatchResult dispatch(NotificationMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        NotificationRoute route = routeResolver.resolve(message).orElse(null);
        if (route == null) {
            return NotificationDispatchResult.noRoute(message);
        }

        List<NotificationDeliveryResult> deliveries =
                new ArrayList<>(route.destinations().size());
        for (NotificationDestination destination : route.destinations()) {
            NotificationDeliveryRequest request =
                    new NotificationDeliveryRequest(deliveryId(message, destination), message, destination);
            deliveries.add(deliver(request));
        }
        return NotificationDispatchResult.completed(message, deliveries);
    }

    /** Sends exactly one already-resolved destination. Used by the durable Outbox consumer. */
    public NotificationDeliveryResult deliver(NotificationDeliveryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        NotificationDestination destination = request.destination();
        NotificationProviderRegistry.Registration registration =
                providerRegistry.find(destination.providerKey()).orElse(null);
        if (registration == null) {
            return NotificationDeliveryResult.failed(
                    request,
                    destination.providerKey(),
                    "PROVIDER_NOT_REGISTERED",
                    "Notification provider is not registered",
                    false);
        }

        Set<NotificationCapability> missing = missingCapabilities(destination, registration.capabilities());
        if (!missing.isEmpty()) {
            String capabilityNames = missing.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
            return NotificationDeliveryResult.failed(
                    request,
                    destination.providerKey(),
                    "PROVIDER_CAPABILITY_MISMATCH",
                    "Notification provider is missing capabilities: " + capabilityNames,
                    false);
        }

        NotificationProvider provider = registration.provider();
        try {
            NotificationProvider.SendResult result = provider.send(request);
            if (result == null) {
                return NotificationDeliveryResult.failed(
                        request,
                        destination.providerKey(),
                        "PROVIDER_EMPTY_RESULT",
                        "Notification provider returned no result",
                        false);
            }
            return NotificationDeliveryResult.delivered(
                    request,
                    destination.providerKey(),
                    result.externalMessageId(),
                    result.providerCode(),
                    result.detail());
        } catch (NotificationProviderException exception) {
            return NotificationDeliveryResult.failed(
                    request,
                    destination.providerKey(),
                    exception.code(),
                    exception.getMessage(),
                    exception.retryable());
        } catch (RuntimeException exception) {
            return NotificationDeliveryResult.failed(
                    request,
                    destination.providerKey(),
                    "PROVIDER_UNEXPECTED_FAILURE",
                    "Unexpected notification provider failure: "
                            + exception.getClass().getSimpleName(),
                    true);
        }
    }

    private static Set<NotificationCapability> missingCapabilities(
            NotificationDestination destination, Set<NotificationCapability> supported) {
        if (destination.requiredCapabilities().isEmpty()) {
            return Set.of();
        }
        EnumSet<NotificationCapability> missing = EnumSet.copyOf(destination.requiredCapabilities());
        missing.removeAll(supported);
        return Set.copyOf(missing);
    }

    private static String deliveryId(NotificationMessage message, NotificationDestination destination) {
        return NotificationDeliveryIds.publicId(NotificationDeliveryIds.deliveryKey(message, destination));
    }
}
