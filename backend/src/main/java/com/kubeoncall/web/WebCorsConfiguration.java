package com.kubeoncall.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.kubeoncall.common.config.KubeOnCallProperties;

@Configuration
public class WebCorsConfiguration implements WebMvcConfigurer {

    private final KubeOnCallProperties properties;

    public WebCorsConfiguration(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        KubeOnCallProperties.Cors cors = properties.getCors();
        if (!cors.isEnabled() || cors.getAllowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(cors.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept")
                .maxAge(3600);
    }
}
