package com.kubeoncall.identity;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.kubeoncall.web.api.v1.V1Principal;

/**
 * Resolves a one-time-issued API token into a live principal.
 *
 * <p>The effective permission set is always the intersection of the token scopes and the owner's
 * current permissions. Disabling the user, revoking the token, expiring it or removing a role
 * therefore takes effect on the next request without minting a replacement token.
 */
@Service
public class ApiTokenAuthenticationService {

    private final ObjectProvider<ApiTokenRepository> tokenRepositoryProvider;
    private final ObjectProvider<IdentityRepository> identityRepositoryProvider;

    public ApiTokenAuthenticationService(
            ObjectProvider<ApiTokenRepository> tokenRepositoryProvider,
            ObjectProvider<IdentityRepository> identityRepositoryProvider) {
        this.tokenRepositoryProvider = tokenRepositoryProvider;
        this.identityRepositoryProvider = identityRepositoryProvider;
    }

    public Optional<V1Principal> authenticate(String plaintext, String sourceIp) {
        if (plaintext == null || !plaintext.startsWith("koc_")) {
            return Optional.empty();
        }
        ApiTokenRepository tokenRepository = tokenRepositoryProvider.getIfAvailable();
        IdentityRepository identityRepository = identityRepositoryProvider.getIfAvailable();
        if (tokenRepository == null
                || !tokenRepository.isAvailable()
                || identityRepository == null
                || !identityRepository.isAvailable()) {
            return Optional.empty();
        }
        Optional<ApiTokenRepository.ApiTokenRow> token =
                tokenRepository.findByHash(ApiTokenRepository.hashPlaintext(plaintext));
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserAccount> owner = identityRepository.findById(token.get().ownerUserId());
        if (owner.isEmpty() || !owner.get().isActive()) {
            return Optional.empty();
        }
        Set<String> effectiveScopes = new LinkedHashSet<>(token.get().scopes());
        effectiveScopes.retainAll(owner.get().permissions());
        tokenRepository.recordUsage(token.get().internalId(), normalizeSourceIp(sourceIp));
        return Optional.of(new V1Principal(owner.get(), Set.copyOf(effectiveScopes), V1Principal.AuthMethod.API_TOKEN));
    }

    private static String normalizeSourceIp(String sourceIp) {
        return sourceIp == null || sourceIp.isBlank() ? "0.0.0.0" : sourceIp.trim();
    }
}
