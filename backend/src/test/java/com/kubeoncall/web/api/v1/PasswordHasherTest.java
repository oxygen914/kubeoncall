package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.kubeoncall.identity.PasswordHasher;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashAndVerifyRoundTrip() {
        char[] password = "correct-horse-battery-staple".toCharArray();
        String hash = hasher.hash(password);
        assertThat(hash).startsWith("$2a$");
        assertThat(hasher.matches(password, hash)).isTrue();
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String hash = hasher.hash("correct-horse-battery-staple".toCharArray());
        assertThat(hasher.matches("wrong-password-value-xx".toCharArray(), hash))
                .isFalse();
    }

    @Test
    void malformedHashDoesNotThrow() {
        assertThat(hasher.matches("whatever-password-xx".toCharArray(), "not-a-hash"))
                .isFalse();
        assertThat(hasher.matches("whatever-password-xx".toCharArray(), "")).isFalse();
        assertThat(hasher.matches(new char[0], "$2a$12$abc")).isFalse();
    }

    @Test
    void emptyPasswordRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> hasher.hash(new char[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
