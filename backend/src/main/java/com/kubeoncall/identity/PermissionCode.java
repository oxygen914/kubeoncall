package com.kubeoncall.identity;

/** All permission codes granted through the built-in roles. Kept in code so tests and the security
 * layer can reference stable constants; the database seed in {@code V1__identity.sql} is the source
 * of truth for what is actually granted. */
public final class PermissionCode {

    public static final String DASHBOARD_READ = "dashboard:read";
    public static final String ALARM_READ = "alarm:read";
    public static final String ALARM_ACKNOWLEDGE = "alarm:acknowledge";
    public static final String ALARM_RECOVER = "alarm:recover";
    public static final String ALARM_SILENCE = "alarm:silence";
    public static final String EXECUTION_READ = "execution:read";
    public static final String EXECUTION_CREATE = "execution:create";
    public static final String EXECUTION_CANCEL = "execution:cancel";
    public static final String EXECUTION_RETRY = "execution:retry";
    public static final String APPROVAL_READ = "approval:read";
    public static final String APPROVAL_DECIDE = "approval:decide";
    public static final String ASK_EXECUTE = "ask:execute";
    public static final String KNOWLEDGE_READ = "knowledge:read";
    public static final String KNOWLEDGE_WRITE = "knowledge:write";
    public static final String KNOWLEDGE_DELETE = "knowledge:delete";
    public static final String KNOWLEDGE_INDEX_MANAGE = "knowledge:index-manage";
    public static final String MEMORY_READ = "memory:read";
    public static final String MEMORY_WRITE = "memory:write";
    public static final String MEMORY_MAINTAIN = "memory:maintain";
    public static final String SKILL_READ = "skill:read";
    public static final String SKILL_MANAGE = "skill:manage";
    public static final String TOOL_READ = "tool:read";
    public static final String POLICY_READ = "policy:read";
    public static final String POLICY_MANAGE = "policy:manage";
    public static final String MAINTENANCE_READ = "maintenance:read";
    public static final String MAINTENANCE_MANAGE = "maintenance:manage";
    public static final String CHANGE_READ = "change:read";
    public static final String CHANGE_WRITE = "change:write";
    public static final String INTEGRATION_READ = "integration:read";
    public static final String INTEGRATION_MANAGE = "integration:manage";
    public static final String USER_READ = "user:read";
    public static final String USER_MANAGE = "user:manage";
    public static final String TOKEN_READ_OWN = "token:read-own";
    public static final String TOKEN_MANAGE_OWN = "token:manage-own";
    public static final String TOKEN_MANAGE_ALL = "token:manage-all";
    public static final String AUDIT_READ = "audit:read";
    public static final String AUDIT_EXPORT = "audit:export";
    public static final String SYSTEM_READ = "system:read";
    public static final String SYSTEM_MANAGE = "system:manage";
    public static final String SANDBOX_READ = "sandbox:read";
    public static final String SANDBOX_EXECUTE = "sandbox:execute";
    public static final String SANDBOX_CANCEL = "sandbox:cancel";
    public static final String SANDBOX_MANAGE = "sandbox:manage";

    private PermissionCode() {}
}
