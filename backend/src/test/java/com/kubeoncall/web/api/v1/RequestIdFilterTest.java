package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.kubeoncall.observability.CorrelationContext;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsFrontendUuidRequestIdAndPopulatesHttpCorrelationMdc() throws Exception {
        String requestId = "req_550e8400-e29b-41d4-a716-446655440000";
        MockHttpServletRequest request = request("GET", "/api/v1/skills");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        request.addHeader(RequestIdFilter.TRACE_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();

        filter.doFilter(
                request, response, (ignoredRequest, ignoredResponse) -> observed.set(MDC.getCopyOfContextMap()));

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(requestId);
        assertThat(observed.get())
                .containsEntry(CorrelationContext.REQUEST_ID, requestId)
                .containsEntry(CorrelationContext.TRACE_ID, "trace-123")
                .containsEntry(CorrelationContext.ROUTE, "GET /api/v1/skills");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void rejectsCrLfAndFallsBackToServerGeneratedSafeId() throws Exception {
        String supplied = "req_550e8400-e29b-41d4-a716-446655440000\r\nforged";
        MockHttpServletRequest request = request("POST", "/api/v1/auth/login");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, supplied);
        request.addHeader(RequestIdFilter.TRACE_ID_HEADER, "trace-ok\r\nforged");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();

        filter.doFilter(
                request, response, (ignoredRequest, ignoredResponse) -> observed.set(MDC.getCopyOfContextMap()));

        String generated = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(generated)
                .startsWith("req_")
                .doesNotContain("\r")
                .doesNotContain("\n")
                .isNotEqualTo(supplied);
        assertThat(observed.get().get(CorrelationContext.TRACE_ID)).isEqualTo(generated);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void clearsMdcWhenDownstreamThrows() {
        MockHttpServletRequest request = request("GET", "/api/v1/system/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                            throw new ServletException("boom");
                        }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
