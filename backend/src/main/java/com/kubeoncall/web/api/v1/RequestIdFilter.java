package com.kubeoncall.web.api.v1;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kubeoncall.observability.CorrelationContext;

/**
 * Assigns an {@code X-Request-Id} to every request, threads it through the SLF4J MDC for log
 * correlation, and echoes it back on the response. Clients may supply their own id; the filter only
 * validates format ({@code req_} prefix + ULID-ish/UUID-ish token) and otherwise generates one, so it
 * can never become an injection vector or be trusted as an identity claim.
 *
 * <p>Runs before authorization filters so security and audit logs always carry a correlation id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_REQUEST_ID = CorrelationContext.REQUEST_ID;
    private static final String PREFIX = "req_";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.clear();
        CorrelationContext.put(CorrelationContext.REQUEST_ID, requestId);
        CorrelationContext.put(CorrelationContext.TRACE_ID, resolveTraceId(request, requestId));
        CorrelationContext.put(CorrelationContext.ROUTE, request.getMethod() + " " + request.getRequestURI());
        try {
            response.setHeader(REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied != null && isValid(supplied)) {
            return supplied;
        }
        return PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Accept {@code req_<token>} where token is 16..64 alphanumeric/hyphen characters. This covers
     * browser-generated UUIDs while rejecting whitespace, CR/LF and other log-injection characters.
     */
    private static boolean isValid(String value) {
        if (value.length() < 20 || value.length() > 68) {
            return false;
        }
        if (!value.startsWith(PREFIX)) {
            return false;
        }
        if (!isAlphaNumeric(value.charAt(PREFIX.length())) || !isAlphaNumeric(value.charAt(value.length() - 1))) {
            return false;
        }
        for (int i = PREFIX.length(); i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = isAlphaNumeric(c) || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAlphaNumeric(char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static String resolveTraceId(HttpServletRequest request, String requestId) {
        String supplied = request.getHeader(TRACE_ID_HEADER);
        if (supplied == null || supplied.isBlank() || supplied.length() > 128) {
            return requestId;
        }
        for (int index = 0; index < supplied.length(); index++) {
            char current = supplied.charAt(index);
            boolean safe = (current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || current == '-'
                    || current == '_'
                    || current == '.'
                    || current == ':';
            if (!safe) {
                return requestId;
            }
        }
        return supplied;
    }

    /** Read the current request id from the MDC for use by controllers and exception handlers. */
    public static String currentRequestId() {
        String value = MDC.get(MDC_REQUEST_ID);
        return value != null ? value : "";
    }

    /** SPI accessor used by components that cannot inject the request directly. */
    public static HttpHeaders headersWithRequestId(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(REQUEST_ID_HEADER, requestId);
        return headers;
    }
}
