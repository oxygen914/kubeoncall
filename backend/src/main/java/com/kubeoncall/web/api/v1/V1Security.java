package com.kubeoncall.web.api.v1;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.kubeoncall.identity.PermissionCode;

/**
 * Helper for controllers to read the current {@link V1Principal} and enforce permissions in code.
 * The security filter chain permits all requests and leaves authorization to controllers so that
 * read endpoints (which should be reachable to inspect availability) can return 403 with the unified
 * envelope rather than a Spring Security default page. Every mutating or sensitive endpoint must
 * call {@link #requirePermission} (or {@link #requireAuthenticated}) as its final authority.
 */
@Component
public class V1Security {

    public V1Principal currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof V1Principal principal) {
            return principal;
        }
        return V1Principal.anonymous();
    }

    public V1Principal requireAuthenticated() {
        V1Principal principal = currentPrincipal();
        if (!principal.isAuthenticated()) {
            throw V1ApiException.unauthenticated("Authentication required");
        }
        return principal;
    }

    public V1Principal requirePermission(String permission) {
        V1Principal principal = requireAuthenticated();
        if (!principal.hasPermission(permission)) {
            throw V1ApiException.forbidden("Insufficient permissions: " + permission);
        }
        return principal;
    }

    public boolean hasPermission(String permission) {
        return currentPrincipal().hasPermission(permission);
    }

    public boolean isAdmin() {
        return currentPrincipal().hasPermission(PermissionCode.SYSTEM_MANAGE);
    }
}
