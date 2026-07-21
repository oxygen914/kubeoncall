package com.kubeoncall.identity;

import org.springframework.stereotype.Component;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;

/**
 * BCrypt password hashing at cost 12. Hashes carry the algorithm parameters and salt inline so they
 * remain self-describing for future migration to Argon2id. Verification is constant-time via the
 * library's {@code verifyStrict} and deliberately returns {@code false} on any malformed hash rather
 * than throwing, so callers cannot distinguish "bad hash" from "wrong password".
 */
@Component
public class PasswordHasher {

    private static final int COST = 12;

    public String hash(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        return BCrypt.with(LongPasswordStrategies.hashSha512(BCrypt.Version.VERSION_2A))
                .hashToString(COST, password);
    }

    public boolean matches(char[] password, String hash) {
        if (password == null || password.length == 0 || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.verifyer(
                            BCrypt.Version.VERSION_2A, LongPasswordStrategies.hashSha512(BCrypt.Version.VERSION_2A))
                    .verifyStrict(password, hash.toCharArray())
                    .verified;
        } catch (Exception ex) {
            return false;
        }
    }
}
