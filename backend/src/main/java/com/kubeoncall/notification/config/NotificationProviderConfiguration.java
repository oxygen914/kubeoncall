package com.kubeoncall.notification.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kubeoncall.notification.provider.dingtalk.DingTalkWebhookNotificationProvider;
import com.kubeoncall.notification.provider.feishu.FeishuWebhookNotificationProvider;
import com.kubeoncall.tool.http.ToolHttpClient;

/** Registers concrete one-way providers only when the new notification subsystem is enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kubeoncall.notifications", name = "enabled", havingValue = "true")
public class NotificationProviderConfiguration {

    @Bean
    public FeishuWebhookNotificationProvider feishuWebhookNotificationProvider(
            ToolHttpClient httpClient, NotificationProperties properties, ObjectProvider<Clock> clockProvider) {
        return new FeishuWebhookNotificationProvider(
                httpClient, clock(clockProvider), properties.getFeishu().getTargets());
    }

    @Bean
    public DingTalkWebhookNotificationProvider dingTalkWebhookNotificationProvider(
            ToolHttpClient httpClient, NotificationProperties properties, ObjectProvider<Clock> clockProvider) {
        return new DingTalkWebhookNotificationProvider(
                httpClient, clock(clockProvider), properties.getDingtalk().getTargets());
    }

    private static Clock clock(ObjectProvider<Clock> provider) {
        Clock configured = provider.getIfAvailable();
        return configured == null ? Clock.systemUTC() : configured;
    }
}
