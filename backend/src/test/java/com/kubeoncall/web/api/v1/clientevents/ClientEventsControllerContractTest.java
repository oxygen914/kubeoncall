package com.kubeoncall.web.api.v1.clientevents;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ClientEventsControllerContractTest {

    private MockMvc mockMvc;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        V1Security security = mock(V1Security.class);
        when(security.requireAuthenticated()).thenReturn(principal());
        meterRegistry = new SimpleMeterRegistry();
        ClientEventRateLimiter limiter =
                new ClientEventRateLimiter(java.time.Clock.systemUTC(), 100, Duration.ofSeconds(60));
        mockMvc = MockMvcBuilders.standaloneSetup(new ClientEventsController(security, limiter, meterRegistry))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void shouldAcceptOnlyBoundedSummaryFieldsAndRateLimitDuplicates() throws Exception {
        String body = """
                {
                  "release": "sha-123",
                  "environment": "production",
                  "route": "/alarms/alm_1",
                  "errorCode": "SERVICE_UNAVAILABLE",
                  "requestId": "req_linked",
                  "browser": "Chrome 126 / MacIntel",
                  "message": "Authorization=Bearer do-not-log-this"
                }
                """;

        mockMvc.perform(post("/api/v1/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
        mockMvc.perform(post("/api/v1/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(false));

        org.assertj.core.api.Assertions.assertThat(meterRegistry
                        .get("kubeoncall_client_events_total")
                        .tag("outcome", "accepted")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        org.assertj.core.api.Assertions.assertThat(meterRegistry
                        .get("kubeoncall_client_events_total")
                        .tag("outcome", "rate_limited")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldRejectQueryBearingRoutesAndOversizedMessages() throws Exception {
        String body = """
                {
                  "release": "dev",
                  "environment": "test",
                  "route": "/alarms?token=secret",
                  "errorCode": "WINDOW_ERROR",
                  "requestId": null,
                  "browser": "Other",
                  "message": "%s"
                }
                """.formatted("x".repeat(241));

        mockMvc.perform(post("/api/v1/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void shouldRejectArbitraryMetadataFieldsInsteadOfLoggingThem() throws Exception {
        String body = """
                {
                  "release": "dev",
                  "environment": "test",
                  "route": "/alarms",
                  "errorCode": "WINDOW_ERROR",
                  "requestId": null,
                  "browser": "Other",
                  "message": "safe summary",
                  "stack": "must-not-be-accepted"
                }
                """;

        mockMvc.perform(post("/api/v1/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private static V1Principal principal() {
        UserAccount user = new UserAccount(
                1,
                "usr_1",
                "alice",
                "Alice",
                null,
                "",
                "BCRYPT",
                1,
                "ACTIVE",
                1,
                null,
                null,
                null,
                0,
                Set.of("OPERATOR"),
                Set.of());
        return new V1Principal(user, Set.of(), V1Principal.AuthMethod.SESSION);
    }
}
