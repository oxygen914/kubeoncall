package com.kubeoncall.evidence;

public record EvidenceResource(String kind, String name, String uid) {

    public EvidenceResource {
        kind = safe(kind);
        name = safe(name);
        uid = safe(uid);
    }

    public boolean hasUid() {
        return !uid.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
