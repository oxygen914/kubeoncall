package com.kubeoncall.memory;

import java.util.Optional;

public interface SessionStore {

    Optional<SessionSnapshot> find(String sessionId);

    void append(String sessionId, SessionTurn turn);

    static SessionStore noop() {
        return new SessionStore() {
            @Override
            public Optional<SessionSnapshot> find(String sessionId) {
                return Optional.empty();
            }

            @Override
            public void append(String sessionId, SessionTurn turn) {
                // Compatibility constructor for unit tests that do not need Redis.
            }
        };
    }
}
