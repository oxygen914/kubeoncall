package com.kubeoncall.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Catch-all security chain for everything outside {@code /api/v1/**}. The legacy {@code /api/**}
 * surface keeps being protected by {@link ApiAccessFilter} (a servlet filter, not Spring Security),
 * so this chain must not impose its own authentication. Without it, spring-boot-starter-security
 * would apply a default HTTP Basic guard to the whole application and break the legacy API and
 * actuator. Ordered after the v1 chain so v1 always wins its security matcher.
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class DefaultSecurityConfig {

    @Bean
    @Order(100)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.disable()));
        return http.build();
    }
}
