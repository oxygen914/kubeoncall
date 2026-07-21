package com.kubeoncall.audit.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AuditDiffTest {

    @Test
    void reportsDeterministicAddedRemovedAndNestedChanges() {
        AuditDiff diff = AuditDiff.between(
                Map.of("removed", true, "status", "FIRING", "nested", Map.of("attempt", 1)),
                Map.of("added", 7, "status", "ACKNOWLEDGED", "nested", Map.of("attempt", 2)));

        assertThat(diff.added()).containsEntry("added", 7);
        assertThat(diff.removed()).containsEntry("removed", true);
        assertThat(diff.changed())
                .containsEntry("status", new AuditDiff.ValueChange("FIRING", "ACKNOWLEDGED"))
                .containsEntry("nested.attempt", new AuditDiff.ValueChange(1, 2));
    }
}
