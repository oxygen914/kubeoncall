package com.kubeoncall.identity;

import java.time.Duration;
import java.util.Optional;

/**
 * Abstraction over session persistence so the web layer never touches Redis directly (ArchUnit:
 * {@code ..web..} must not depend on {@code org.springframework.data.redis..}). Implementations
 * remain in the {@code identity} package and the web layer depends on this interface only.
 */
public interface SessionStore {

    String create(SessionRecord record, Duration ttl);

    Optional<SessionRecord> find(String sessionId);

    void replace(String sessionId, SessionRecord record, Duration ttl);

    void delete(String sessionId);

    void deleteByUser(long userId);
}
