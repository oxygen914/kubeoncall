package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.PermissionCode;

/**
 * Asserts the legacy-token compatibility path mirrors the §6.5 sandbox role mapping, so configured
 * Viewer/Operator/Admin tokens cannot bypass the permission matrix that the database seed enforces.
 */
class V1AuthenticationFilterSandboxPermissionsTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyViewerTokenGrantsOnlySandboxRead() throws Exception {
        V1Principal principal = authenticateLegacy(
                "viewer-secret", props -> props.getApiSecurity().setViewerToken("viewer-secret"));
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_READ)).isTrue();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_EXECUTE)).isFalse();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_CANCEL)).isFalse();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_MANAGE)).isFalse();
    }

    @Test
    void legacyOperatorTokenGrantsSandboxReadExecuteCancelButNotManage() throws Exception {
        V1Principal principal =
                authenticateLegacy("op-secret", props -> props.getApiSecurity().setOperatorToken("op-secret"));
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_READ)).isTrue();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_EXECUTE)).isTrue();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_CANCEL)).isTrue();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_MANAGE)).isFalse();
    }

    @Test
    void legacyAdminTokenGrantsAllSandboxPermissions() throws Exception {
        V1Principal principal = authenticateLegacy(
                "admin-secret", props -> props.getApiSecurity().setAdminToken("admin-secret"));
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_READ)).isTrue();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_EXECUTE)).isTrue();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_CANCEL)).isTrue();
        assertThat(principal.hasPermission(PermissionCode.SANDBOX_MANAGE)).isTrue();
    }

    private V1Principal authenticateLegacy(String token, java.util.function.Consumer<KubeOnCallProperties> configurator)
            throws Exception {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        configurator.accept(properties);
        V1AuthenticationFilter filter = new V1AuthenticationFilter(mock(AuthService.class), properties, true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/status");
        request.setRequestURI("/api/v1/system/status");
        request.addHeader("Authorization", "Bearer " + token);
        AtomicReference<V1Principal> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
            Object principal =
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            captured.set((V1Principal) principal);
        });
        return captured.get();
    }
}
