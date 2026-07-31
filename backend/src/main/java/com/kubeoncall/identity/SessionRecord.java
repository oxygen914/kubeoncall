package com.kubeoncall.identity;

import java.time.Instant;

/**
 * Session payload persisted in Redis. Holds only what the request path needs: identity reference,
 * auth version for invalidation, idle/absolute expiry, and the CSRF secret bound to this session.
 * The session id itself is the Redis key and is never stored inside the payload.
 */
public record SessionRecord(
        long userId,
        String userPublicId,
        String username,
        String displayName,
        long authVersion,
        Instant issuedAt,
        Instant lastAccessAt,
        Instant expiresAt,
        String csrfSecret) {

    public boolean isExpired(Instant now, long idleTimeoutSeconds) {
        if (expiresAt != null && now.isAfter(expiresAt)) {
            return true;
        }
        Instant idleDeadline = lastAccessAt.plusSeconds(idleTimeoutSeconds);
        return now.isAfter(idleDeadline);
    }

    public SessionRecord touch(Instant now, long idleTimeoutSeconds, long maxTimeoutSeconds) {
        Instant newLastAccess = now;
        Instant absoluteDeadline = issuedAt.plusSeconds(maxTimeoutSeconds);
        Instant idleDeadline = newLastAccess.plusSeconds(idleTimeoutSeconds);
        Instant newExpiresAt = absoluteDeadline.isBefore(idleDeadline) ? absoluteDeadline : idleDeadline;
        return new SessionRecord(
                userId,
                userPublicId,
                username,
                displayName,
                authVersion,
                issuedAt,
                newLastAccess,
                newExpiresAt,
                csrfSecret);
    }
}
