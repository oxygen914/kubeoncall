package com.kubeoncall.common.config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    /** Stable old-pattern to v1 successor mapping, used for HTTP Link migration guidance. */
    private Map<String, String> successors = new LinkedHashMap<>();
    /** Individually retired legacy mapping patterns. They return 410 while other legacy routes remain available. */
    private Set<String> retiredEndpoints = new LinkedHashSet<>();

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

    public Map<String, String> getSuccessors() {
        return successors;
    }

    public void setSuccessors(Map<String, String> successors) {
        this.successors = successors == null ? new LinkedHashMap<>() : new LinkedHashMap<>(successors);
    }

    public Set<String> getRetiredEndpoints() {
        return retiredEndpoints;
    }

    public void setRetiredEndpoints(Set<String> retiredEndpoints) {
        this.retiredEndpoints =
                retiredEndpoints == null ? new LinkedHashSet<>() : new LinkedHashSet<>(retiredEndpoints);
    }
}
