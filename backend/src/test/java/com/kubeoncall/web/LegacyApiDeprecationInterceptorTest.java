package com.kubeoncall.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LegacyApiDeprecationInterceptorTest {

    @Test
    void marksAnnotatedLegacyHandlersAndRecordsABoundedEndpointMetric() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LegacyApiDeprecationInterceptor interceptor = interceptor(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/status");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler(LegacyHandler.class)))
                .isTrue();
        interceptor.afterCompletion(request, response, handler(LegacyHandler.class), null);

        assertThat(response.getHeader("Deprecation")).isEqualTo("@1782864000");
        assertThat(response.getHeader("Sunset")).isEqualTo("Thu, 31 Dec 2026 23:59:59 GMT");
        assertThat(registry.counter(
                                "kubeoncall.legacy_api.requests",
                                "endpoint",
                                "_api_status",
                                "method",
                                "get",
                                "status",
                                "2xx")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void leavesNonLegacyHandlersUntouched() throws Exception {
        LegacyApiDeprecationInterceptor interceptor = interceptor(new SimpleMeterRegistry());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, handler(CurrentHandler.class)))
                .isTrue();

        assertThat(response.getHeader("Deprecation")).isNull();
        assertThat(response.getHeader("Sunset")).isNull();
    }

    @Test
    void advertisesSuccessorAndCanRetireOneMappedLegacyEndpoint() throws Exception {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getLegacyApi().setSuccessors(Map.of("/api/status", "/api/v1/system/status"));
        properties.getLegacyApi().setRetiredEndpoints(Set.of("/api/status"));
        LegacyApiDeprecationInterceptor interceptor = interceptor(new SimpleMeterRegistry(), properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/status");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler(LegacyHandler.class)))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(response.getHeader("Link")).isEqualTo("</api/v1/system/status>; rel=\"successor-version\"");
    }

    private static LegacyApiDeprecationInterceptor interceptor(MeterRegistry registry) {
        return interceptor(registry, new KubeOnCallProperties());
    }

    private static LegacyApiDeprecationInterceptor interceptor(
            MeterRegistry registry, KubeOnCallProperties properties) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new LegacyApiDeprecationInterceptor(properties, new KubeOnCallMetricsService(provider));
    }

    private static HandlerMethod handler(Class<?> type) throws Exception {
        Method method = type.getMethod("get");
        return new HandlerMethod(type.getDeclaredConstructor().newInstance(), method);
    }

    @LegacyApiController
    static class LegacyHandler {
        public void get() {}
    }

    static class CurrentHandler {
        public void get() {}
    }
}
