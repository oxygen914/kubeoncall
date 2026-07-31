package com.kubeoncall.common.config;

/**
 * Authentication and session configuration for the {@code /api/v1} console surface. Cookie/CSRF
 * names are namespaced away from integration webhooks so the three auth domains (browser session,
 * API token, webhook) never share a credential.
 */
public class AuthProperties {

    private String sessionCookieName = "KOC_SESSION";
    private String csrfCookieName = "KOC_CSRF";
    private boolean sessionCookieSecure = false;
    private long sessionIdleTimeoutSeconds = 1800;
    private long sessionMaxTimeoutSeconds = 28800;
    private int loginMaxAttempts = 5;
    private long loginLockDurationSeconds = 900;
    private boolean legacyTokenCompat = true;
    private boolean bootstrapAdminEnabled = false;
    private String bootstrapAdminUsername;
    private String bootstrapAdminPassword;

    public String getSessionCookieName() {
        return sessionCookieName;
    }

    public void setSessionCookieName(String sessionCookieName) {
        this.sessionCookieName = sessionCookieName;
    }

    public String getCsrfCookieName() {
        return csrfCookieName;
    }

    public void setCsrfCookieName(String csrfCookieName) {
        this.csrfCookieName = csrfCookieName;
    }

    public boolean isSessionCookieSecure() {
        return sessionCookieSecure;
    }

    public void setSessionCookieSecure(boolean sessionCookieSecure) {
        this.sessionCookieSecure = sessionCookieSecure;
    }

    public long getSessionIdleTimeoutSeconds() {
        return sessionIdleTimeoutSeconds;
    }

    public void setSessionIdleTimeoutSeconds(long sessionIdleTimeoutSeconds) {
        this.sessionIdleTimeoutSeconds = sessionIdleTimeoutSeconds;
    }

    public long getSessionMaxTimeoutSeconds() {
        return sessionMaxTimeoutSeconds;
    }

    public void setSessionMaxTimeoutSeconds(long sessionMaxTimeoutSeconds) {
        this.sessionMaxTimeoutSeconds = sessionMaxTimeoutSeconds;
    }

    public int getLoginMaxAttempts() {
        return loginMaxAttempts;
    }

    public void setLoginMaxAttempts(int loginMaxAttempts) {
        this.loginMaxAttempts = loginMaxAttempts;
    }

    public long getLoginLockDurationSeconds() {
        return loginLockDurationSeconds;
    }

    public void setLoginLockDurationSeconds(long loginLockDurationSeconds) {
        this.loginLockDurationSeconds = loginLockDurationSeconds;
    }

    public boolean isLegacyTokenCompat() {
        return legacyTokenCompat;
    }

    public void setLegacyTokenCompat(boolean legacyTokenCompat) {
        this.legacyTokenCompat = legacyTokenCompat;
    }

    public boolean isBootstrapAdminEnabled() {
        return bootstrapAdminEnabled;
    }

    public void setBootstrapAdminEnabled(boolean bootstrapAdminEnabled) {
        this.bootstrapAdminEnabled = bootstrapAdminEnabled;
    }

    public String getBootstrapAdminUsername() {
        return bootstrapAdminUsername;
    }

    public void setBootstrapAdminUsername(String bootstrapAdminUsername) {
        this.bootstrapAdminUsername = bootstrapAdminUsername;
    }

    public String getBootstrapAdminPassword() {
        return bootstrapAdminPassword;
    }

    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }
}
