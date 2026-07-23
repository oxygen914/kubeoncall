package com.kubeoncall.web.api.v1;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.identity.AuthService;

/**
 * Security chain for the {@code /api/v1} surface only. It is ordered before the default chain so it
 * owns everything under {@code /api/v1/**} while the legacy {@code /api/**} paths keep being
 * governed by {@code ApiAccessFilter}. Authentication is session-cookie based via
 * {@link V1AuthenticationFilter}; legacy bearer tokens are accepted as a compatibility shim while
 * the static-token deprecation window is open. CSRF is enforced on mutating methods; read methods
 * and the login endpoint are exempt.
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class V1SecurityConfig {

    private final AuthService authService;
    private final V1AuthenticationFilter authenticationFilter;
    private final ObjectMapper objectMapper;

    public V1SecurityConfig(
            AuthService authService, V1AuthenticationFilter authenticationFilter, ObjectMapper objectMapper) {
        this.authService = authService;
        this.authenticationFilter = authenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain v1SecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new V1CsrfFilter(authService, objectMapper), V1AuthenticationFilter.class);
        return http.build();
    }
}
