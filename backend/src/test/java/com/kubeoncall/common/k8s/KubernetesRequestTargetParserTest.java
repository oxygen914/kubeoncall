package com.kubeoncall.common.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KubernetesRequestTargetParserTest {

    @Test
    void extractsPodAndNamespaceFromChineseOperationalQuestion() {
        KubernetesRequestTargetParser.Target target = KubernetesRequestTargetParser.parse(
                "请只读分析 kubeoncall-system 命名空间中 Pod " + "kubernetes-tool-adapter-58ff54d4d9-ss25p 的当前状态");

        assertThat(target.namespace()).isEqualTo("kubeoncall-system");
        assertThat(target.resourceKind()).isEqualTo("Pod");
        assertThat(target.resourceName()).isEqualTo("kubernetes-tool-adapter-58ff54d4d9-ss25p");
        assertThat(target.hasResource()).isTrue();
    }

    @Test
    void onlyParsesTheCurrentUserSectionWhenPlannerContextIsPresent() {
        KubernetesRequestTargetParser.Target target =
                KubernetesRequestTargetParser.parse("SOP example: Pod ignored-example in default namespace\n"
                        + "Current user: inspect namespace kubeoncall-system Pod/adapter-abc");

        assertThat(target.namespace()).isEqualTo("kubeoncall-system");
        assertThat(target.resourceKind()).isEqualTo("Pod");
        assertThat(target.resourceName()).isEqualTo("adapter-abc");
    }
}
