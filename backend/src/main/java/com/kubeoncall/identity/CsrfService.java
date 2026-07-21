package com.kubeoncall.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Double-submit CSRF tokens. On login the server stores a per-session {@code csrfSecret} and emits a
 * readable {@code KOC_CSRF} cookie whose value is {@code deriveToken(secret)}. Mutating requests
 * must echo the same value back in {@code X-CSRF-Token}; the server re-derives from the session
 * secret and compares in constant time. The secret never leaves the server, so stealing the cookie
 * alone is not enough to forge a request without XSS.
 */
@Component
public class CsrfService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** Deterministic token derivation so the server can validate without storing the token itself. */
    public String deriveToken(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("koc-csrf:" + secret).getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public boolean isValid(String sessionSecret, String suppliedToken) {
        if (sessionSecret == null || sessionSecret.isBlank() || suppliedToken == null || suppliedToken.isBlank()) {
            return false;
        }
        String expected = deriveToken(sessionSecret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8));
    }
}
