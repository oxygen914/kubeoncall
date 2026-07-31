package com.kubeoncall.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.kubeoncall.identity.PermissionCode;

class TaskPermissionPolicyTest {

    @Test
    void mapsEveryGovernanceTaskToItsOwningReadPermission() {
        assertThat(TaskPermissionPolicy.requiredPermission("KNOWLEDGE_IMPORT", "knowledge_import"))
                .isEqualTo(PermissionCode.KNOWLEDGE_READ);
        assertThat(TaskPermissionPolicy.requiredPermission("MEMORY_EXTRACTION", "memory_extraction"))
                .isEqualTo(PermissionCode.MEMORY_READ);
        assertThat(TaskPermissionPolicy.requiredPermission("SKILL_RELOAD", "skill"))
                .isEqualTo(PermissionCode.SKILL_READ);
        assertThat(TaskPermissionPolicy.requiredPermission("ASK_EXECUTION", "workflow_execution"))
                .isEqualTo(PermissionCode.EXECUTION_READ);
    }

    @Test
    void resourceTypeRemainsAuthoritativeWhenTaskTypeIsGeneric() {
        assertThat(TaskPermissionPolicy.requiredPermission("IMPORT", "knowledge_import"))
                .isEqualTo(PermissionCode.KNOWLEDGE_READ);
        assertThat(TaskPermissionPolicy.requiredPermission(null, "memory")).isEqualTo(PermissionCode.MEMORY_READ);
    }

    @Test
    void sandboxTasksRequireSandboxExecute() {
        assertThat(TaskPermissionPolicy.requiredPermission("SANDBOX_DISPATCH", "sandbox_run"))
                .isEqualTo(PermissionCode.SANDBOX_EXECUTE);
        assertThat(TaskPermissionPolicy.requiredPermission("ASYNC_DISPATCH", "sandbox"))
                .isEqualTo(PermissionCode.SANDBOX_EXECUTE);
    }

    @Test
    void sandboxClassificationPrecedesOverlappingReadOnlyDomains() {
        // A sandbox task whose resource also names a read-only domain must stay classified as
        // sandbox:execute, not downgrade to skill/knowledge/memory read.
        assertThat(TaskPermissionPolicy.requiredPermission("SANDBOX_DISPATCH", "sandbox_skill"))
                .isEqualTo(PermissionCode.SANDBOX_EXECUTE);
        assertThat(TaskPermissionPolicy.requiredPermission("SANDBOX_DISPATCH", "sandbox_knowledge"))
                .isEqualTo(PermissionCode.SANDBOX_EXECUTE);
        assertThat(TaskPermissionPolicy.requiredPermission("SANDBOX_DISPATCH", "sandbox_memory"))
                .isEqualTo(PermissionCode.SANDBOX_EXECUTE);
        // Non-sandbox tasks on the same domains still resolve to their read permission.
        assertThat(TaskPermissionPolicy.requiredPermission("SKILL_RELOAD", "skill"))
                .isEqualTo(PermissionCode.SKILL_READ);
    }
}
