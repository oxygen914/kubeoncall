package com.kubeoncall.common.config;

import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Explicit real-model wiring for the planner.
 *
 * <p>The normal local profile stays in RULE_FALLBACK and does not create any provider client. A
 * deployment that selects REAL_MODEL must supply a non-empty key and therefore fails at startup
 * instead of silently pretending that model-backed planning is available.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("('${kubeoncall.ai-operations.planner-mode:RULE_FALLBACK}').equalsIgnoreCase('REAL_MODEL')"
        + " || ('${kubeoncall.ai-operations.planner-mode:RULE_FALLBACK}').equalsIgnoreCase('RULE_ASSISTED')")
public class PlannerChatConfiguration {

    @Bean("plannerChatClient")
    @Primary
    ChatClient plannerChatClient(
            KubeOnCallProperties properties,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "KUBEONCALL_PLANNER_MODE=REAL_MODEL/RULE_ASSISTED requires SPRING_AI_OPENAI_API_KEY");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "KUBEONCALL_PLANNER_MODE=REAL_MODEL/RULE_ASSISTED requires SPRING_AI_OPENAI_BASE_URL");
        }
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(properties.getAiOperations().getPlannerModel());
        options.setTemperature(0.1f);
        options.setMaxTokens(2048);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                Duration.ofMillis(Math.max(100, properties.getAiOperations().getPlannerConnectTimeoutMillis())));
        requestFactory.setReadTimeout(
                Duration.ofMillis(Math.max(1000, properties.getAiOperations().getPlannerReadTimeoutMillis())));
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        OpenAiApi api = new OpenAiApi(baseUrl.trim(), apiKey.trim(), restClientBuilder, WebClient.builder());
        return ChatClient.create(new OpenAiChatModel(api, options));
    }
}
