package com.kubeoncall.common.config;

import java.util.ArrayList;
import java.util.List;

public class CorsProperties {

    private boolean enabled = true;
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://127.0.0.1:8081", "http://localhost:8081"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }
}
