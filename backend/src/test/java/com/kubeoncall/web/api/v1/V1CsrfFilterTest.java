package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.CsrfService;
import com.kubeoncall.identity.UserAccount;

/**
 * Verifies {@link V1CsrfFilter} enforcement behavior that the service-level tests do not cover:
 * mutating requests authenticated via a browser session are rejected without a matching
 * {@code X-CSRF-Token}, safe methods and the login endpoint are exempt, and API-token/anonymous
 * callers are not subject to CSRF (they carry their own credential).
 */
class V1CsrfFilterTest {

    private CsrfService csrfService;
    private V1CsrfFilter filter;
    private boolean chainProceeded;

    @BeforeEach
    void setUp() {
        csrfService = new CsrfService();
        AuthService authService = mock(AuthService.class);
        when(authService.csrfService()).thenReturn(csrfService);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        filter = new V1CsrfFilter(authService, objectMapper);
        chainProceeded = false;
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void safeMethodsPassWithoutCsrf() throws Exception {
        authenticateSession("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/alarms");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        assertThat(chainProceeded).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void loginEndpointIsExempt() throws Exception {
        authenticateSession("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        assertThat(chainProceeded).isTrue();
    }

    @Test
    void clientTelemetryPostIsExemptForSendBeacon() throws Exception {
        authenticateSession("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/client-events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        assertThat(chainProceeded).isTrue();
    }

    @Test
    void sessionPostWithoutCsrfTokenIsRejected() throws Exception {
        authenticateSession("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alarms/alm_1/acknowledgements");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        assertThat(chainProceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN").contains("CSRF");
    }

    @Test
    void sessionPostWithMatchingCsrfTokenPasses() throws Exception {
        String secret = "secret";
        authenticateSession(secret);
        String token = csrfService.deriveToken(secret);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alarms/alm_1/acknowledgements");
        request.addHeader("X-CSRF-Token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        assertThat(chainProceeded).isTrue();
    }

    @Test
    void sessionPostWithWrongCsrfTokenIsRejected() throws Exception {
        authenticateSession("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alarms/alm_1/acknowledgements");
        request.addHeader("X-CSRF-Token", csrfService.deriveToken("different-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        assertThat(chainProceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void anonymousPostIsExemptFromCsrf() throws Exception {
        // No authentication in context → anonymous; CSRF does not apply (the auth layer will 401).
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alarms/alm_1/acknowledgements");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        assertThat(chainProceeded).isTrue();
    }

    @Test
    void legacyTokenPostIsExemptFromCsrf() throws Exception {
        authenticateLegacyToken();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alarms/alm_1/acknowledgements");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainProceeded = true);
        // Legacy-token callers carry an explicit credential and cannot be CSRF'd.
        assertThat(chainProceeded).isTrue();
    }

    private void authenticateSession(String csrfSecret) {
        UserAccount user = user();
        V1Principal principal = new V1Principal(user, java.util.Set.of("OPERATOR"), V1Principal.AuthMethod.SESSION);
        V1AuthenticationToken token = new V1AuthenticationToken(principal);
        token.setDetails(csrfSecret);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private void authenticateLegacyToken() {
        UserAccount user = user();
        V1Principal principal = new V1Principal(user, java.util.Set.of("ADMIN"), V1Principal.AuthMethod.LEGACY_TOKEN);
        SecurityContextHolder.getContext().setAuthentication(new V1AuthenticationToken(principal));
    }

    private static UserAccount user() {
        return new UserAccount(
                1L,
                "usr_1",
                "alice",
                "Alice",
                null,
                "",
                "BCRYPT",
                1L,
                "ACTIVE",
                1L,
                null,
                null,
                null,
                0,
                java.util.Set.of("OPERATOR"),
                java.util.Set.of("alarm:acknowledge"));
    }
}
