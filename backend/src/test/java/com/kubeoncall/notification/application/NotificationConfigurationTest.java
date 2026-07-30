package com.kubeoncall.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;
import com.kubeoncall.notification.spi.NotificationRouteResolver;

class NotificationConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(NotificationConfiguration.class);

    @Test
    void wiresInertDefaultsWithoutProvidersOrRoutes() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NotificationProviderRegistry.class);
            assertThat(context).hasSingleBean(NotificationDispatcher.class);
            assertThat(context.getBean(NotificationProviderRegistry.class).size())
                    .isZero();

            NotificationDispatchResult result =
                    context.getBean(NotificationDispatcher.class).dispatch(message());

            assertThat(result.status()).isEqualTo(NotificationDispatchResult.Status.NO_ROUTE);
            assertThat(result.deliveries()).isEmpty();
        });
    }

    @Test
    void bindsLogicalRouteWithoutPuttingWebhookCredentialsInDomainTarget() {
        contextRunner
                .withPropertyValues(
                        "kubeoncall.notifications.routes[0].key=oncall",
                        "kubeoncall.notifications.routes[0].destinations[0].id=feishu-primary",
                        "kubeoncall.notifications.routes[0].destinations[0].provider-key=feishu",
                        "kubeoncall.notifications.routes[0].destinations[0].target=infra-primary",
                        "kubeoncall.notifications.routes[0].destinations[0].required-capabilities[0]=GROUP_WEBHOOK")
                .run(context -> {
                    NotificationRouteResolver resolver = context.getBean(NotificationRouteResolver.class);

                    var route = resolver.resolve(message()).orElseThrow();

                    assertThat(route.destinations()).hasSize(1);
                    assertThat(route.destinations().get(0).providerKey()).isEqualTo("feishu");
                    assertThat(route.destinations().get(0).target()).isEqualTo("infra-primary");
                });
    }

    private static NotificationMessage message() {
        return new NotificationMessage(
                "evt-1",
                "alarm.firing",
                "oncall",
                NotificationPriority.HIGH,
                "NodeDown",
                "Node is unavailable",
                Map.of(),
                List.of(),
                Instant.parse("2026-07-30T01:00:00Z"));
    }
}
