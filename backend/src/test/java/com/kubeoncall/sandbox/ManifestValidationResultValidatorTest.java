package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ManifestValidationResultValidatorTest {

    @Test
    void acceptsOnlyTheFixedManifestValidationOutputContract() {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(ManifestValidationResultValidator.isValid("""
                        {"schemaVersion":"v1","valid":true,"findings":[],
                         "toolVersions":{"rulesetVersion":"sandbox-manifest-rules-v1"}}
                        """, mapper)).isTrue();
        assertThat(ManifestValidationResultValidator.isValid(
                        "{"
                                + "\"schemaVersion\":\"v1\",\"valid\":true,\"findings\":[],\"toolVersions\":{},\"command\":\"kubectl apply\"}",
                        mapper))
                .isFalse();
    }
}
