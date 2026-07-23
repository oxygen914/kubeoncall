package com.kubeoncall.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the legacy deprecation contract after Spring has resolved the controller mapping. */
@Configuration
public class LegacyApiDeprecationConfiguration implements WebMvcConfigurer {

    private final LegacyApiDeprecationInterceptor interceptor;

    public LegacyApiDeprecationConfiguration(LegacyApiDeprecationInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
