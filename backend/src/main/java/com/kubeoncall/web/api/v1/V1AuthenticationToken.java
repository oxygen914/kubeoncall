package com.kubeoncall.web.api.v1;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Spring Security {@link org.springframework.security.core.Authentication} backed by a
 * {@link V1Principal}. Authenticated when the auth method is not anonymous; credentials are never
 * retained (sessions and tokens are validated upstream).
 */
public class V1AuthenticationToken extends AbstractAuthenticationToken {

    private final V1Principal principal;

    public V1AuthenticationToken(V1Principal principal) {
        super(principal.authorities());
        this.principal = principal;
        setAuthenticated(principal.isAuthenticated());
    }

    public V1AuthenticationToken(V1Principal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(principal.isAuthenticated());
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public V1Principal getPrincipal() {
        return principal;
    }
}
