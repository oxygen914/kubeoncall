package com.kubeoncall.domain.rag;

import java.util.Locale;

public enum RetrieveMethod {
    KEYWORD,
    VECTOR,
    HYBRID;

    public static RetrieveMethod fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        try {
            return RetrieveMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return HYBRID;
        }
    }
}
