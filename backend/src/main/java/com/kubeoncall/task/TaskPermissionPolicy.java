package com.kubeoncall.task;

import java.util.Locale;

import com.kubeoncall.identity.PermissionCode;

/** Maps a generic async task to the permission of the business domain that owns it. */
public final class TaskPermissionPolicy {

    private TaskPermissionPolicy() {}

    public static String requiredPermission(String taskType, String resourceType) {
        String normalizedResource = normalize(resourceType);
        String normalizedTask = taskType == null ? "" : taskType.trim().toUpperCase(Locale.ROOT);
        if (normalizedResource.contains("migration") || normalizedTask.startsWith("MIGRATION_")) {
            return PermissionCode.SYSTEM_MANAGE;
        }
        if (normalizedResource.contains("knowledge")
                || normalizedResource.contains("import")
                || normalizedTask.startsWith("KNOWLEDGE_")) {
            return PermissionCode.KNOWLEDGE_READ;
        }
        if (normalizedResource.contains("memory") || normalizedTask.startsWith("MEMORY_")) {
            return PermissionCode.MEMORY_READ;
        }
        if (normalizedResource.contains("skill") || normalizedTask.startsWith("SKILL_")) {
            return PermissionCode.SKILL_READ;
        }
        return PermissionCode.EXECUTION_READ;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
