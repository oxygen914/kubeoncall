package com.kubeoncall.migration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.identity.UserAccount;

/** Resolves a legacy free-text actor only through an exact username or an explicit operator mapping. */
@Service
public class LegacyActorResolver {

    private final ObjectProvider<IdentityRepository> identityRepositoryProvider;
    private final KubeOnCallProperties properties;

    public LegacyActorResolver(
            ObjectProvider<IdentityRepository> identityRepositoryProvider, KubeOnCallProperties properties) {
        this.identityRepositoryProvider = identityRepositoryProvider;
        this.properties = properties;
    }

    public Optional<ResolvedActor> resolve(String legacyActor) {
        String normalizedActor = normalize(legacyActor);
        if (normalizedActor == null) {
            return Optional.empty();
        }
        IdentityRepository identities = identityRepositoryProvider.getIfAvailable();
        if (identities == null || !identities.isAvailable()) {
            return Optional.empty();
        }
        Map<String, String> mappings =
                parseMappings(properties.getDataMigration().getLegacyActorUsernameMappings());
        String username = mappings.getOrDefault(normalizedActor, normalizedActor);
        return identities
                .findByUsername(username)
                .map(account ->
                        new ResolvedActor(normalizedActor, username, account, mappings.containsKey(normalizedActor)));
    }

    public String mappedUsername(String legacyActor) {
        String normalizedActor = normalize(legacyActor);
        if (normalizedActor == null) {
            return null;
        }
        return parseMappings(properties.getDataMigration().getLegacyActorUsernameMappings())
                .getOrDefault(normalizedActor, normalizedActor);
    }

    static Map<String, String> parseMappings(String source) {
        if (source == null || source.isBlank()) {
            return Map.of();
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String pair : source.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalArgumentException("legacy actor mapping must use legacyActor=username");
            }
            String legacyActor = normalize(trimmed.substring(0, separator));
            String username = normalize(trimmed.substring(separator + 1));
            if (legacyActor == null || username == null || mappings.putIfAbsent(legacyActor, username) != null) {
                throw new IllegalArgumentException("legacy actor mapping contains a duplicate or blank actor");
            }
        }
        return Map.copyOf(mappings);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public record ResolvedActor(String legacyActor, String username, UserAccount account, boolean explicitMapping) {}
}
