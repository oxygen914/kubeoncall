package com.kubeoncall.common.config;

import java.time.Instant;

/**
 * Controls the legacy {@code /api/*} surface retirement (WBS-11 Phase 6). The legacy controllers
 * stay registered by default so the old Console and any scripts using the three-role tokens keep
 * working during the migration window. Setting {@code enabled=false} stops the legacy controllers
 * from being picked up, so the v1 surface becomes the only API — but the flag never deletes data or
 * drops Redis state, so re-enabling is a config-only rollback.
 */
public class LegacyApiProperties {

    private boolean enabled = true;
    private Instant deprecatedAt = Instant.parse("2026-07-01T00:00:00Z");
    private Instant sunsetAt = Instant.parse("2026-12-31T23:59:59Z");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Instant exposed in the RFC 9745 {@code Deprecation} response header for legacy routes. */
    public Instant getDeprecatedAt() {
        return deprecatedAt;
    }

    public void setDeprecatedAt(Instant deprecatedAt) {
        this.deprecatedAt = deprecatedAt;
    }

    /** Last supported instant exposed in the HTTP {@code Sunset} response header. */
    public Instant getSunsetAt() {
        return sunsetAt;
    }

    public void setSunsetAt(Instant sunsetAt) {
        this.sunsetAt = sunsetAt;
    }
}
