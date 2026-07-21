package com.kubeoncall.common.config;

/**
 * Controls the legacy {@code /api/*} surface retirement (WBS-11 Phase 6). The legacy controllers
 * stay registered by default so the old Console and any scripts using the three-role tokens keep
 * working during the migration window. Setting {@code enabled=false} stops the legacy controllers
 * from being picked up, so the v1 surface becomes the only API — but the flag never deletes data or
 * drops Redis state, so re-enabling is a config-only rollback.
 */
public class LegacyApiProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
