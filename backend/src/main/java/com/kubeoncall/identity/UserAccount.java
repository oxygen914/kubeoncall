package com.kubeoncall.identity;

import java.time.Instant;
import java.util.Set;

/**
 * Read-optimised view of a user together with their roles and effective permissions. Loaded by
 * {@link IdentityRepository} via explicit SQL (per the data-access ADR: Spring Data JDBC + explicit
 * SQL rather than hiding queries behind derived methods). Permissions are the union across all
 * assigned roles; resource-state constraints stay in application services.
 */
public record UserAccount(
        long id,
        String publicId,
        String username,
        String displayName,
        String email,
        String passwordHash,
        String passwordAlgorithm,
        long passwordVersion,
        String status,
        long authVersion,
        long version,
        Instant passwordChangedAt,
        Instant lastLoginAt,
        Instant lockedUntil,
        int failedLoginCount,
        Set<String> roles,
        Set<String> permissions) {

    /** Compatibility constructor for callers that do not need the persistent resource revision. */
    public UserAccount(
            long id,
            String publicId,
            String username,
            String displayName,
            String email,
            String passwordHash,
            String passwordAlgorithm,
            long passwordVersion,
            String status,
            long authVersion,
            Instant passwordChangedAt,
            Instant lastLoginAt,
            Instant lockedUntil,
            int failedLoginCount,
            Set<String> roles,
            Set<String> permissions) {
        this(
                id,
                publicId,
                username,
                displayName,
                email,
                passwordHash,
                passwordAlgorithm,
                passwordVersion,
                status,
                authVersion,
                authVersion,
                passwordChangedAt,
                lastLoginAt,
                lockedUntil,
                failedLoginCount,
                roles,
                permissions);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isPasswordChangeRequired() {
        return "PASSWORD_CHANGE_REQUIRED".equals(status);
    }
}
