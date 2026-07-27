package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class GeneratedCodeResultValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsOnlyTheBoundedStructuredGeneratedCodeResult() {
        assertThat(GeneratedCodeResultValidator.isValid("""
                        {"schemaVersion":"v1","status":"SUCCEEDED","findings":["ok"],
                         "evidenceReferences":["log:pod/a"],"summary":"done"}
                        """, objectMapper)).isTrue();
        assertThat(GeneratedCodeResultValidator.isValid("""
                        {"schemaVersion":"v1","status":"SUCCEEDED","findings":[],
                         "evidenceReferences":[],"summary":"done","command":"kubectl delete"}
                        """, objectMapper)).isFalse();
        assertThat(GeneratedCodeResultValidator.isValid("not-json", objectMapper))
                .isFalse();
    }
}
