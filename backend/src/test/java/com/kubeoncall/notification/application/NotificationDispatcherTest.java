package com.kubeoncall.notification.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;
import com.kubeoncall.notification.domain.NotificationRoute;
import com.kubeoncall.notification.spi.NotificationProvider;
import com.kubeoncall.notification.spi.NotificationProviderException;

class NotificationDispatcherTest {

    @Test
    void fansOutToEveryDestinationWithDeterministicDeliveryIds() {
        StubProvider feishu = provider("feishu", request -> accepted("om-feishu"));
        StubProvider dingtalk = provider("dingtalk", request -> accepted("om-dingtalk"));
        NotificationDispatcher dispatcher = dispatcher(
                List.of(feishu, dingtalk),
                route(
                        destination("feishu-primary", "feishu", NotificationCapability.GROUP_WEBHOOK),
                        destination("dingtalk-backup", "dingtalk", NotificationCapability.GROUP_WEBHOOK)));

        NotificationDispatchResult result = dispatcher.dispatch(message());

        assertEquals(NotificationDispatchResult.Status.DELIVERED, result.status());
        assertEquals(2, result.deliveries().size());
        assertEquals(
                NotificationDeliveryIds.publicId(
                        NotificationDeliveryIds.deliveryKey(message(), route("feishu-primary", "feishu"))),
                result.deliveries().get(0).deliveryId());
        assertEquals(
                NotificationDeliveryIds.publicId(
                        NotificationDeliveryIds.deliveryKey(message(), route("dingtalk-backup", "dingtalk"))),
                result.deliveries().get(1).deliveryId());
        assertEquals(1, feishu.requests.size());
        assertEquals(1, dingtalk.requests.size());
        assertFalse(result.hasRetryableFailure());
    }

    @Test
    void isolatesRetryableProviderFailureFromOtherDestinations() {
        StubProvider feishu = provider("feishu", request -> {
            throw new NotificationProviderException("FEISHU_RATE_LIMITED", "Feishu rate limited", true);
        });
        StubProvider dingtalk = provider("dingtalk", request -> accepted("om-dingtalk"));
        NotificationDispatcher dispatcher = dispatcher(
                List.of(feishu, dingtalk),
                route(
                        destination("feishu-primary", "feishu", NotificationCapability.GROUP_WEBHOOK),
                        destination("dingtalk-backup", "dingtalk", NotificationCapability.GROUP_WEBHOOK)));

        NotificationDispatchResult result = dispatcher.dispatch(message());

        assertEquals(NotificationDispatchResult.Status.PARTIAL_FAILURE, result.status());
        assertEquals("FEISHU_RATE_LIMITED", result.deliveries().get(0).code());
        assertTrue(result.deliveries().get(0).retryable());
        assertTrue(result.deliveries().get(1).delivered());
        assertTrue(result.hasRetryableFailure());
    }

    @Test
    void reportsMissingProviderAsNonRetryableConfigurationFailure() {
        NotificationDispatcher dispatcher = dispatcher(
                List.of(), route(destination("feishu-primary", "feishu", NotificationCapability.GROUP_WEBHOOK)));

        NotificationDispatchResult result = dispatcher.dispatch(message());

        assertEquals(NotificationDispatchResult.Status.FAILED, result.status());
        assertEquals("PROVIDER_NOT_REGISTERED", result.deliveries().get(0).code());
        assertFalse(result.deliveries().get(0).retryable());
    }

    @Test
    void rejectsDestinationWhenProviderLacksRequiredCapability() {
        StubProvider feishu = new StubProvider(
                "feishu", Set.of(NotificationCapability.GROUP_WEBHOOK), request -> accepted("om-feishu"));
        NotificationDispatcher dispatcher = dispatcher(
                List.of(feishu),
                route(destination(
                        "feishu-interactive",
                        "feishu",
                        NotificationCapability.GROUP_WEBHOOK,
                        NotificationCapability.INTERACTIVE_CALLBACK)));

        NotificationDispatchResult result = dispatcher.dispatch(message());

        assertEquals(NotificationDispatchResult.Status.FAILED, result.status());
        assertEquals("PROVIDER_CAPABILITY_MISMATCH", result.deliveries().get(0).code());
        assertTrue(result.deliveries().get(0).detail().contains("INTERACTIVE_CALLBACK"));
        assertTrue(feishu.requests.isEmpty());
    }

    @Test
    void returnsNoRouteWithoutCallingProviders() {
        StubProvider feishu = provider("feishu", request -> accepted("om-feishu"));
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(feishu));
        NotificationDispatcher dispatcher =
                new NotificationDispatcher(registry, new StaticNotificationRouteResolver(List.of()));

        NotificationDispatchResult result = dispatcher.dispatch(message());

        assertEquals(NotificationDispatchResult.Status.NO_ROUTE, result.status());
        assertTrue(result.deliveries().isEmpty());
        assertTrue(feishu.requests.isEmpty());
    }

    private static NotificationDispatcher dispatcher(
            List<? extends NotificationProvider> providers, NotificationRoute route) {
        return new NotificationDispatcher(
                new NotificationProviderRegistry(providers), new StaticNotificationRouteResolver(List.of(route)));
    }

    private static NotificationRoute route(NotificationDestination... destinations) {
        return new NotificationRoute("oncall", List.of(destinations));
    }

    private static NotificationDestination destination(
            String id, String providerKey, NotificationCapability... capabilities) {
        return new NotificationDestination(
                id, providerKey, id + "-target", Set.of(capabilities), Map.of("audience", "infra"));
    }

    private static NotificationDestination route(String id, String providerKey) {
        return destination(id, providerKey, NotificationCapability.GROUP_WEBHOOK);
    }

    private static NotificationMessage message() {
        return new NotificationMessage(
                "evt-1",
                "alarm.firing",
                "oncall",
                NotificationPriority.CRITICAL,
                "NodeDown",
                "Node exporter is unavailable",
                Map.of("resource", "node-a"),
                List.of(),
                Instant.parse("2026-07-30T01:00:00Z"));
    }

    private static StubProvider provider(
            String key, Function<NotificationDeliveryRequest, NotificationProvider.SendResult> sender) {
        return new StubProvider(key, Set.of(NotificationCapability.GROUP_WEBHOOK), sender);
    }

    private static NotificationProvider.SendResult accepted(String externalMessageId) {
        return new NotificationProvider.SendResult(externalMessageId, "0", "accepted");
    }

    private static final class StubProvider implements NotificationProvider {

        private final String key;
        private final Set<NotificationCapability> capabilities;
        private final Function<NotificationDeliveryRequest, SendResult> sender;
        private final List<NotificationDeliveryRequest> requests = new ArrayList<>();

        private StubProvider(
                String key,
                Set<NotificationCapability> capabilities,
                Function<NotificationDeliveryRequest, SendResult> sender) {
            this.key = key;
            this.capabilities = capabilities;
            this.sender = sender;
        }

        @Override
        public String providerKey() {
            return key;
        }

        @Override
        public Set<NotificationCapability> capabilities() {
            return capabilities;
        }

        @Override
        public SendResult send(NotificationDeliveryRequest request) {
            requests.add(request);
            return sender.apply(request);
        }
    }
}
