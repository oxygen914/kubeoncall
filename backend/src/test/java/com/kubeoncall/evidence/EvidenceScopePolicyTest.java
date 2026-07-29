package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

class EvidenceScopePolicyTest {

    @Test
    void failsClosedWhenNoScopeAllowlistIsConfigured() {
        EvidenceScopePolicy policy = new EvidenceScopePolicy(new KubeOnCallProperties());

        assertThat(policy.rejection(scope("prod", "payments"))).contains("CLUSTER_NOT_ALLOWED");
    }

    @Test
    void acceptsOnlyExplicitCaseInsensitiveClusterAndNamespaceMatches() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setAllowedClusters(List.of("TEST-01"));
        properties.getAiOperations().setAllowedNamespaces(List.of("Payments"));
        EvidenceScopePolicy policy = new EvidenceScopePolicy(properties);

        assertThat(policy.rejection(scope("test-01", "payments"))).isEmpty();
        assertThat(policy.rejection(scope("test-02", "payments"))).contains("CLUSTER_NOT_ALLOWED");
        assertThat(policy.rejection(scope("test-01", "default"))).contains("NAMESPACE_NOT_ALLOWED");
    }

    private static EvidenceCollectionScope scope(String cluster, String namespace) {
        Instant end = Instant.parse("2026-07-29T10:00:00Z");
        return new EvidenceCollectionScope(
                "exe_1",
                cluster,
                "test",
                namespace,
                new EvidenceResource("Pod", "payment-api", "uid-1"),
                end.minusSeconds(300),
                end);
    }
}
