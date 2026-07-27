package com.kubeoncall.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.identity.ApiTokenRepository.ApiTokenRow;
import com.kubeoncall.web.api.v1.V1Principal;

/**
 * Proves the SBX-02 revocation contract — a sandbox-scoped API token loses its sandbox permission
 * on the very next request once the owner's role no longer grants it, and a revoked token never
 * authenticates. Uses Mockito stubs for the repositories so the per-request reload + intersection
 * logic in {@link ApiTokenAuthenticationService} is exercised directly.
 */
class ApiTokenAuthenticationServiceSandboxTest {

    private static final String PLAINTEXT = "koc_sandbox_secret_value";

    @Test
    void sandboxScopePassesWhenOwnerRoleGrantsIt() {
        ApiTokenRepository tokens = stubTokens(Optional.of(tokenWithScopes(List.of(PermissionCode.SANDBOX_EXECUTE))));
        IdentityRepository identities =
                stubIdentity(ownerWithPermissions(Set.of(PermissionCode.SANDBOX_READ, PermissionCode.SANDBOX_EXECUTE)));
        ApiTokenAuthenticationService service = newService(tokens, identities);

        Optional<V1Principal> principal = service.authenticate(PLAINTEXT, "127.0.0.1");

        assertThat(principal).isPresent();
        assertThat(principal.get().hasPermission(PermissionCode.SANDBOX_EXECUTE))
                .isTrue();
        assertThat(principal.get().hasPermission(PermissionCode.SANDBOX_READ)).isFalse();
    }

    @Test
    void sandboxScopeFailsImmediatelyAfterOwnerRoleRevoked() {
        // Token carries both sandbox:read and sandbox:execute, but the owner's role was revoked down
        // to sandbox:read only. The intersection drops sandbox:execute on the next request — no
        // replacement token needed.
        ApiTokenRepository tokens = stubTokens(
                Optional.of(tokenWithScopes(List.of(PermissionCode.SANDBOX_READ, PermissionCode.SANDBOX_EXECUTE))));
        IdentityRepository identities = stubIdentity(ownerWithPermissions(Set.of(PermissionCode.SANDBOX_READ)));
        ApiTokenAuthenticationService service = newService(tokens, identities);

        Optional<V1Principal> principal = service.authenticate(PLAINTEXT, "127.0.0.1");

        assertThat(principal).isPresent();
        assertThat(principal.get().hasPermission(PermissionCode.SANDBOX_EXECUTE))
                .as("revoked role must drop the sandbox scope on the next request")
                .isFalse();
        assertThat(principal.get().hasPermission(PermissionCode.SANDBOX_READ)).isTrue();
    }

    @Test
    void revokedTokenNeverAuthenticates() {
        // findByHash returns empty (revoked tokens are excluded), so authentication fails outright.
        ApiTokenRepository tokens = stubTokens(Optional.empty());
        IdentityRepository identities = stubIdentity(ownerWithPermissions(Set.of(PermissionCode.SANDBOX_MANAGE)));
        ApiTokenAuthenticationService service = newService(tokens, identities);

        assertThat(service.authenticate(PLAINTEXT, "127.0.0.1")).isEmpty();
    }

    private static ApiTokenRow tokenWithScopes(List<String> scopes) {
        return new ApiTokenRow(
                1L,
                "tok_1",
                99L,
                "usr_owner",
                "owner",
                "owner-token",
                "koc_sand",
                scopes,
                Instant.parse("2027-01-01T00:00:00Z"),
                null,
                null,
                1L,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static UserAccount ownerWithPermissions(Set<String> permissions) {
        return new UserAccount(
                99L,
                "usr_owner",
                "owner",
                "Owner",
                null,
                "hash",
                "BCRYPT",
                1L,
                "ACTIVE",
                1L,
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                0,
                Set.of("OPERATOR"),
                permissions);
    }

    @SuppressWarnings("unchecked")
    private static ApiTokenAuthenticationService newService(ApiTokenRepository tokens, IdentityRepository identities) {
        ObjectProvider<ApiTokenRepository> tokenProvider = mock(ObjectProvider.class);
        when(tokenProvider.getIfAvailable()).thenReturn(tokens);
        ObjectProvider<IdentityRepository> identityProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identities);
        return new ApiTokenAuthenticationService(tokenProvider, identityProvider);
    }

    private static ApiTokenRepository stubTokens(Optional<ApiTokenRow> findByHashResult) {
        ApiTokenRepository tokens = mock(ApiTokenRepository.class);
        when(tokens.isAvailable()).thenReturn(true);
        when(tokens.findByHash(any(byte[].class))).thenReturn(findByHashResult);
        org.mockito.Mockito.doNothing().when(tokens).recordUsage(anyLong(), any());
        return tokens;
    }

    private static IdentityRepository stubIdentity(UserAccount owner) {
        IdentityRepository identities = mock(IdentityRepository.class);
        when(identities.isAvailable()).thenReturn(true);
        when(identities.findById(anyLong())).thenReturn(Optional.of(owner));
        return identities;
    }
}
