package com.kubeoncall.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.web.dto.ApiErrorCode;
import com.kubeoncall.web.dto.ApiErrorResponse;

/** Enforces the ChangeEvent request limit before Spring materializes an unbounded byte array. */
@Component
public class ChangeEventPayloadLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventPayloadLimitFilter.class);
    private static final String PATH_PREFIX = "/api/integrations/change-events/";

    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;
    private final ExecutionAuditService auditService;
    private final ObjectMapper objectMapper;

    public ChangeEventPayloadLimitFilter(
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            ExecutionAuditService auditService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.metricsService = metricsService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith(request.getContextPath() + PATH_PREFIX)
                || !properties.getChangeEvents().isWebhookEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        int maxBytes = Math.max(1024, properties.getChangeEvents().getWebhookMaxPayloadBytes());
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String provider = provider(request);
        metricsService.recordChangeEvent("payload_too_large", provider);
        try {
            auditService.recordChangeEventOperation(
                    "payload_too_large",
                    "FAILED",
                    "Change event rejected: payload_too_large",
                    Instant.now(),
                    Map.of("provider", provider, "errorType", "WebhookPayloadTooLargeException"));
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to persist oversized ChangeEvent audit: errorType={}",
                    ex.getClass().getSimpleName());
        }
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                new ApiErrorResponse(
                        Instant.now(),
                        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        ApiErrorCode.BUSINESS_ERROR,
                        "Change-event webhook payload exceeds the configured limit",
                        request.getRequestURI(),
                        Map.of()));
    }

    private String provider(HttpServletRequest request) {
        String path = request.getRequestURI();
        int start = path.indexOf(PATH_PREFIX);
        if (start < 0) {
            return "unknown";
        }
        String value = path.substring(start + PATH_PREFIX.length());
        int separator = value.indexOf('/');
        return (separator < 0 ? value : value.substring(0, separator)).trim().toLowerCase();
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // The cached body is synchronously available.
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
