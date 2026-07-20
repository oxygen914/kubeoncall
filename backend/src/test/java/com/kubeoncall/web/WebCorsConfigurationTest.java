package com.kubeoncall.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import com.kubeoncall.common.config.KubeOnCallProperties;

class WebCorsConfigurationTest {

    @Test
    void shouldAllowConfiguredConsoleOriginForApiRoutes() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getCors().setAllowedOrigins(List.of("https://console.example.com"));
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsConfiguration(properties).addCorsMappings(registry);

        CorsConfiguration configuration = registry.configurations().get("/api/**");
        assertEquals(List.of("https://console.example.com"), configuration.getAllowedOrigins());
        assertTrue(configuration.getAllowedMethods().contains("OPTIONS"));
        assertTrue(configuration.getAllowedHeaders().contains("Authorization"));
    }

    @Test
    void shouldNotRegisterCorsMappingWhenDisabled() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getCors().setEnabled(false);
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsConfiguration(properties).addCorsMappings(registry);

        assertTrue(registry.configurations().isEmpty());
    }

    private static class InspectableCorsRegistry extends CorsRegistry {

        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
