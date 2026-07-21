package com.kubeoncall.identity;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates opaque session ids with at least 128 bits of entropy. Ids are URL-safe base64 of 16
 * random bytes; the {@code koc_} prefix lets Redis tooling distinguish them from other keys. The
 * normalize step rejects anything that does not match the expected shape so a malformed cookie can
 * never become a Redis key-injection vector.
 */
public final class SessionIdGenerator {

    private static final String PREFIX = "koc_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SessionIdGenerator() {}

    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return PREFIX + ENCODER.encodeToString(bytes);
    }

    public static String normalize(String sessionId) {
        if (sessionId == null || !sessionId.startsWith(PREFIX)) {
            return null;
        }
        String body = sessionId.substring(PREFIX.length());
        if (body.length() < 16 || body.length() > 32) {
            return null;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return sessionId;
    }
}
