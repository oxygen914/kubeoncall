package com.kubeoncall.evidence;

public record SopEvidenceReference(String sopId, String version, String source, String section) {

    public SopEvidenceReference {
        sopId = safe(sopId);
        version = safe(version);
        source = safe(source);
        section = safe(section);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
