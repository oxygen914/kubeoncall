package com.kubeoncall.migration;

import static com.kubeoncall.migration.RedisInventoryService.classify;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies the WBS-11 Redis key taxonomy so the inventory report categorises keys correctly. */
class RedisInventoryClassificationTest {

    @Test
    void activeBusinessFactsAreMigratable() {
        assertThat(classify("alarm-active:sha256:abc")).isEqualTo("active-business-fact");
        assertThat(classify("alarm-ack:fp_1")).isEqualTo("active-business-fact");
        assertThat(classify("alarm-silence-approval:fp_2")).isEqualTo("active-business-fact");
        assertThat(classify("alarm-recovery:fp_3")).isEqualTo("active-business-fact");
        assertThat(classify("approval-request:ap_1")).isEqualTo("active-business-fact");
    }

    @Test
    void historicalAuditIsMigratableWithinRetention() {
        assertThat(classify("execution-audit:exec_1")).isEqualTo("historical-audit");
        assertThat(classify("alarm-change-event:id:ce_1")).isEqualTo("historical-audit");
    }

    @Test
    void skillStateIsMigratable() {
        assertThat(classify("skill-reload:v1")).isEqualTo("skill-state");
        assertThat(classify("skill:disabled:node-notready-triage")).isEqualTo("skill-state");
    }

    @Test
    void sessionAndGraphStateAreNotMigrated() {
        assertThat(classify("koc:session:koc_abc")).isEqualTo("session-graphstate");
        assertThat(classify("ask-session:sess_1")).isEqualTo("session-graphstate");
        assertThat(classify("graph-state:exec_1")).isEqualTo("session-graphstate");
    }

    @Test
    void shortTermDedupAndLocksAreNotMigrated() {
        assertThat(classify("alarm-dedup:fp_1")).isEqualTo("short-term-dedup-lock");
        assertThat(classify("alarm-delivery:dlv_1")).isEqualTo("short-term-dedup-lock");
        assertThat(classify("alarm-lifecycle-reservation:fp_1")).isEqualTo("short-term-dedup-lock");
        assertThat(classify("graph-state-resume-lease:exec_1")).isEqualTo("short-term-dedup-lock");
    }

    @Test
    void rebuildableCachesAreNotMigrated() {
        assertThat(classify("alarm-suppression:node:worker-1")).isEqualTo("rebuildable-cache");
        assertThat(classify("alarm-maintenance-window:1")).isEqualTo("rebuildable-cache");
        assertThat(classify("alarm-aggregate:scope_1")).isEqualTo("rebuildable-cache");
        assertThat(classify("memory-extraction:status:task_1")).isEqualTo("rebuildable-cache");
        assertThat(classify("tool:cache:k8s")).isEqualTo("rebuildable-cache");
        assertThat(classify("koc-csrf:secret_1")).isEqualTo("rebuildable-cache");
    }

    @Test
    void unknownKeysAreFlagged() {
        assertThat(classify("some-new-prefix:foo")).isEqualTo("unclassified");
        assertThat(classify(null)).isEqualTo("unknown");
    }
}
