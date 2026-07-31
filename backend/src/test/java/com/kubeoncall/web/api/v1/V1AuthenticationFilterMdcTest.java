package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.observability.CorrelationContext;

class V1AuthenticationFilterMdcTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void addsAuthenticatedIdentityToMdcAndRemovesItAfterRequest() throws Exception {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getApiSecurity().setAdminToken("admin-secret");
        V1AuthenticationFilter filter = new V1AuthenticationFilter(mock(AuthService.class), properties, true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/status");
        request.setRequestURI("/api/v1/system/status");
        request.addHeader("Authorization", "Bearer admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(CorrelationContext.REQUEST_ID, "req_parent");
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();

        filter.doFilter(
                request, response, (ignoredRequest, ignoredResponse) -> observed.set(MDC.getCopyOfContextMap()));

        assertThat(observed.get())
                .containsEntry(CorrelationContext.REQUEST_ID, "req_parent")
                .containsEntry(CorrelationContext.USER_ID, "legacy_admin")
                .containsEntry(CorrelationContext.AUTH_METHOD, "LEGACY_TOKEN");
        assertThat(MDC.get(CorrelationContext.REQUEST_ID)).isEqualTo("req_parent");
        assertThat(MDC.get(CorrelationContext.USER_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.AUTH_METHOD)).isNull();
    }

    @Test
    void anonymousRequestStillCarriesExplicitAuthMethodInsideChain() throws Exception {
        V1AuthenticationFilter filter =
                new V1AuthenticationFilter(mock(AuthService.class), new KubeOnCallProperties(), false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/status");
        request.setRequestURI("/api/v1/system/status");
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> observed.set(MDC.getCopyOfContextMap()));

        assertThat(observed.get())
                .containsEntry(CorrelationContext.USER_ID, "anonymous")
                .containsEntry(CorrelationContext.AUTH_METHOD, "ANONYMOUS");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
