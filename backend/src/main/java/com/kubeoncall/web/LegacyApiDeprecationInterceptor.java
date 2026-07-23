package com.kubeoncall.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

/**
 * Marks and measures only controllers explicitly annotated as {@link LegacyApiController}. Ingest
 * webhooks remain outside this retirement contract even when they are mounted under {@code /api}.
 */
@Component
public class LegacyApiDeprecationInterceptor implements HandlerInterceptor {

    private static final String LEGACY_ENDPOINT_ATTRIBUTE =
            LegacyApiDeprecationInterceptor.class.getName() + ".endpoint";

    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metrics;

    public LegacyApiDeprecationInterceptor(KubeOnCallProperties properties, KubeOnCallMetricsService metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || AnnotationUtils.findAnnotation(method.getBeanType(), LegacyApiController.class) == null) {
            return true;
        }
        KubeOnCallProperties.LegacyApi legacy = properties.getLegacyApi();
        Instant deprecatedAt = legacy.getDeprecatedAt();
        Instant sunsetAt = legacy.getSunsetAt();
        if (deprecatedAt != null) {
            response.setHeader("Deprecation", "@" + deprecatedAt.getEpochSecond());
        }
        if (sunsetAt != null) {
            response.setHeader(
                    "Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(sunsetAt.atOffset(ZoneOffset.UTC)));
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        request.setAttribute(
                LEGACY_ENDPOINT_ATTRIBUTE, pattern instanceof String value ? value : request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        Object endpoint = request.getAttribute(LEGACY_ENDPOINT_ATTRIBUTE);
        if (endpoint instanceof String value) {
            metrics.recordLegacyApiRequest(value, request.getMethod(), response.getStatus());
        }
    }
}
