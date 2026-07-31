package com.kubeoncall.web.api.v1;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.kubeoncall.identity.UserAccount;

/**
 * Authenticated principal for the {@code /api/v1} surface. Carries the resolved user, the raw
 * permission codes (used by manual {@code hasPermission} checks and {@code @PreAuthorize} expressions)
 * and Spring Security authorities derived from role codes ({@code ROLE_<CODE>}). The
 * {@code authMethod} field records which of the three auth domains authenticated the request so audit
 * can distinguish browser session, API token and legacy token traffic.
 */
public record V1Principal(UserAccount user, Set<String> permissions, AuthMethod authMethod) {

    public enum AuthMethod {
        SESSION,
        LEGACY_TOKEN,
        API_TOKEN,
        ANONYMOUS
    }

    public String name() {
        return user == null ? "anonymous" : user.username();
    }

    public Collection<? extends GrantedAuthority> authorities() {
        if (user == null || user.roles() == null) {
            return Collections.emptyList();
        }
        return user.roles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }

    public boolean isAuthenticated() {
        return authMethod != AuthMethod.ANONYMOUS && user != null;
    }

    public static V1Principal anonymous() {
        return new V1Principal(null, Set.of(), AuthMethod.ANONYMOUS);
    }
}
