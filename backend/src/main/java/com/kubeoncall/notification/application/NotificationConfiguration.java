package com.kubeoncall.notification.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kubeoncall.notification.config.NotificationProperties;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationRoute;
import com.kubeoncall.notification.spi.NotificationProvider;
import com.kubeoncall.notification.spi.NotificationRouteResolver;

/** Wires an inert notification substrate until routes and concrete providers are configured. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {

    @Bean
    public NotificationProviderRegistry notificationProviderRegistry(
            ObjectProvider<NotificationProvider> providerProvider) {
        return new NotificationProviderRegistry(providerProvider.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean(NotificationRouteResolver.class)
    public NotificationRouteResolver notificationRouteResolver(NotificationProperties properties) {
        List<NotificationRoute> routes = new ArrayList<>(properties.getRoutes().size());
        for (NotificationProperties.Route configuredRoute : properties.getRoutes()) {
            List<NotificationDestination> destinations =
                    new ArrayList<>(configuredRoute.getDestinations().size());
            for (NotificationProperties.Destination configuredDestination : configuredRoute.getDestinations()) {
                destinations.add(new NotificationDestination(
                        configuredDestination.getId(),
                        configuredDestination.getProviderKey(),
                        configuredDestination.getTarget(),
                        configuredDestination.getRequiredCapabilities(),
                        configuredDestination.getAttributes()));
            }
            routes.add(new NotificationRoute(configuredRoute.getKey(), destinations));
        }
        return new StaticNotificationRouteResolver(routes);
    }

    @Bean
    public NotificationDispatcher notificationDispatcher(
            NotificationProviderRegistry providerRegistry, NotificationRouteResolver routeResolver) {
        return new NotificationDispatcher(providerRegistry, routeResolver);
    }

    @Bean
    public NotificationPublisher notificationPublisher(
            ObjectProvider<NotificationSubmissionService> submissionServiceProvider) {
        return new NotificationPublisher(submissionServiceProvider);
    }
}
