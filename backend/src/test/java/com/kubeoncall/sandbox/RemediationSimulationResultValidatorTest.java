package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class RemediationSimulationResultValidatorTest {

    @Test
    void acceptsOnlyBoundedSimulationObservationsThatRequireCleanup() {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(RemediationSimulationResultValidator.isValid("""
                        {"schemaVersion":"v1","outcome":"PASSED","checks":{"readiness":"passed"},
                         "cleanupRequired":true,"findings":[]}
                        """, mapper)).isTrue();
        assertThat(RemediationSimulationResultValidator.isValid(
                        "{\"schemaVersion\":\"v1\",\"outcome\":\"PASSED\",\"checks\":{},\"cleanupRequired\":false}",
                        mapper))
                .isFalse();
        assertThat(RemediationSimulationResultValidator.isValid(
                        "{\"schemaVersion\":\"v1\",\"outcome\":\"PASSED\",\"checks\":{},\"cleanupRequired\":true,\"command\":\"kubectl apply\"}",
                        mapper))
                .isFalse();
    }
}
