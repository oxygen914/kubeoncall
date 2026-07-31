package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.CsrfService;
import com.kubeoncall.identity.SessionRecord;
import com.kubeoncall.identity.UserAccount;

/**
 * Exercises the production {@link V1SecurityConfig} through its real Spring Security filter chain.
 * This catches ordering regressions that isolated filter tests cannot detect: session resolution
 * must happen before the CSRF guard decides whether a mutating request needs a token.
 */
class V1SecurityFilterChainTest {

    private AnnotationConfigWebApplicationContext context;
    private AuthService authService;
    private CsrfService csrfService;
    private ProbeController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityTestConfiguration.class, V1SecurityConfig.class);
        context.refresh();

        authService = context.getBean(AuthService.class);
        csrfService = context.getBean(CsrfService.class);
        controller = new ProbeController();
        Filter securityFilterChain = context.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(securityFilterChain)
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void sessionPostWithoutCsrfTokenIsRejectedBeforeController() throws Exception {
        stubSession("session-1", "csrf-secret");

        mockMvc.perform(post("/api/v1/security/probe").cookie(new Cookie("KOC_SESSION", "session-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verify(authService).resolveSession("session-1");
        assertThat(controller.probeInvocations()).isZero();
    }

    @Test
    void sessionPostWithCorrectCsrfTokenEntersController() throws Exception {
        String secret = "csrf-secret";
        stubSession("session-1", secret);

        mockMvc.perform(post("/api/v1/security/probe")
                        .cookie(new Cookie("KOC_SESSION", "session-1"))
                        .header("X-CSRF-Token", csrfService.deriveToken(secret)))
                .andExpect(status().isOk())
                .andExpect(content().string("probe"));

        verify(authService).resolveSession("session-1");
        assertThat(controller.probeInvocations()).isEqualTo(1);
    }

    @Test
    void legacyBearerPostIsExemptFromCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/security/probe").header("Authorization", "Bearer legacy-operator-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("probe"));

        assertThat(controller.probeInvocations()).isEqualTo(1);
    }

    @Test
    void loginPostIsExemptEvenWhenSessionCookieIsPresent() throws Exception {
        stubSession("session-1", "csrf-secret");

        mockMvc.perform(post("/api/v1/auth/login").cookie(new Cookie("KOC_SESSION", "session-1")))
                .andExpect(status().isOk())
                .andExpect(content().string("login"));

        verify(authService).resolveSession("session-1");
        assertThat(controller.loginInvocations()).isEqualTo(1);
    }

    @Test
    void v1SecurityChainDoesNotInterceptLegacyApi() throws Exception {
        mockMvc.perform(post("/api/legacy/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("legacy"));

        assertThat(controller.legacyInvocations()).isEqualTo(1);
    }

    private void stubSession(String sessionId, String csrfSecret) {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        UserAccount user = new UserAccount(
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
                Set.of("OPERATOR"),
                Set.of("alarm:acknowledge"));
        SessionRecord session = new SessionRecord(
                user.id(),
                user.publicId(),
                user.username(),
                user.displayName(),
                user.authVersion(),
                now,
                now,
                now.plusSeconds(3600),
                csrfSecret);
        when(authService.resolveSession(sessionId))
                .thenReturn(Optional.of(new AuthService.ResolvedSession(sessionId, session, user)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class SecurityTestConfiguration {

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        CsrfService csrfService(AuthService authService) {
            CsrfService csrfService = new CsrfService();
            when(authService.csrfService()).thenReturn(csrfService);
            return csrfService;
        }

        @Bean
        KubeOnCallProperties kubeOnCallProperties() {
            KubeOnCallProperties properties = new KubeOnCallProperties();
            properties.getApiSecurity().setOperatorToken("legacy-operator-token");
            return properties;
        }

        @Bean
        V1AuthenticationFilter v1AuthenticationFilter(AuthService authService, KubeOnCallProperties properties) {
            return new V1AuthenticationFilter(authService, properties, true);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @RestController
    static class ProbeController {

        private final AtomicInteger probeInvocations = new AtomicInteger();
        private final AtomicInteger loginInvocations = new AtomicInteger();
        private final AtomicInteger legacyInvocations = new AtomicInteger();

        @PostMapping("/api/v1/security/probe")
        String probe() {
            probeInvocations.incrementAndGet();
            return "probe";
        }

        @PostMapping("/api/v1/auth/login")
        String login() {
            loginInvocations.incrementAndGet();
            return "login";
        }

        @PostMapping("/api/legacy/probe")
        String legacy() {
            legacyInvocations.incrementAndGet();
            return "legacy";
        }

        int probeInvocations() {
            return probeInvocations.get();
        }

        int loginInvocations() {
            return loginInvocations.get();
        }

        int legacyInvocations() {
            return legacyInvocations.get();
        }
    }
}
