package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.kubeoncall.identity.CsrfService;

class CsrfServiceTest {

    private final CsrfService csrf = new CsrfService();

    @Test
    void derivedTokenMatchesSecret() {
        String secret = csrf.newSecret();
        String token = csrf.deriveToken(secret);
        assertThat(token).isNotBlank();
        assertThat(csrf.isValid(secret, token)).isTrue();
    }

    @Test
    void tokenIsDeterministicPerSecret() {
        String secret = csrf.newSecret();
        assertThat(csrf.deriveToken(secret)).isEqualTo(csrf.deriveToken(secret));
    }

    @Test
    void differentSecretsProduceDifferentTokens() {
        assertThat(csrf.deriveToken(csrf.newSecret())).isNotEqualTo(csrf.deriveToken(csrf.newSecret()));
    }

    @Test
    void wrongTokenRejected() {
        String secret = csrf.newSecret();
        assertThat(csrf.isValid(secret, "totally-different-token")).isFalse();
        assertThat(csrf.isValid(null, csrf.deriveToken(secret))).isFalse();
        assertThat(csrf.isValid(secret, null)).isFalse();
        assertThat(csrf.isValid("", "")).isFalse();
    }
}
