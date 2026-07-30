package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;

class EvidenceScopeResolverTest {

    @Test
    void authenticatedScopeWinsWhileMissingResourceFieldsComeFromTheQuestion() {
        EvidenceScopeResolver resolver = new EvidenceScopeResolver(new KubeOnCallProperties());
        GraphState state = new GraphState();
        state.setExecutionId("exe_1");
        state.setUserRequest("请只读分析 other-system 命名空间中 Pod kubernetes-tool-adapter-abc 的状态");
        state.getContext()
                .put(
                        "requestScope",
                        Map.of(
                                "cluster", "local",
                                "namespace", "kubeoncall-system"));

        EvidenceCollectionScope scope = resolver.resolve(state, "current-scope");

        assertThat(scope.cluster()).isEqualTo("local");
        assertThat(scope.namespace()).isEqualTo("kubeoncall-system");
        assertThat(scope.resource().kind()).isEqualTo("Pod");
        assertThat(scope.resource().name()).isEqualTo("kubernetes-tool-adapter-abc");
    }
}
