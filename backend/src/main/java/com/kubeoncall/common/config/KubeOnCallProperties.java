package com.kubeoncall.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kubeoncall")
public class KubeOnCallProperties {

    private final Agent agent = new Agent();
    private final Approval approval = new Approval();
    private final Rag rag = new Rag();
    private final Storage storage = new Storage();
    private final Mcp mcp = new Mcp();
    private final Workflow workflow = new Workflow();
    private final Audit audit = new Audit();
    private final Integrations integrations = new Integrations();
    private final Alarm alarm = new Alarm();
    private final ChangeEvents changeEvents = new ChangeEvents();
    private final Memory memory = new Memory();
    private final Skill skill = new Skill();
    private final ApiSecurity apiSecurity = new ApiSecurity();
    private final Cors cors = new Cors();

    public Agent getAgent() {
        return agent;
    }

    public Approval getApproval() {
        return approval;
    }

    public Rag getRag() {
        return rag;
    }

    public Storage getStorage() {
        return storage;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public Integrations getIntegrations() {
        return integrations;
    }

    public Audit getAudit() {
        return audit;
    }

    public Alarm getAlarm() {
        return alarm;
    }

    public Memory getMemory() {
        return memory;
    }

    public ChangeEvents getChangeEvents() {
        return changeEvents;
    }

    public Skill getSkill() {
        return skill;
    }

    public ApiSecurity getApiSecurity() {
        return apiSecurity;
    }

    public Cors getCors() {
        return cors;
    }

    public static class Agent extends AgentProperties {}

    public static class Approval extends ApprovalProperties {}

    public static class Rag extends RagProperties {}

    public static class Storage extends StorageProperties {

        public static class Minio extends MinioProperties {}
    }

    public static class Mcp extends McpProperties {}

    public static class Workflow extends WorkflowProperties {}

    public static class Audit extends AuditProperties {}

    public static class Alarm extends AlarmProperties {}

    public static class Memory extends MemoryProperties {}

    public static class ChangeEvents extends ChangeEventProperties {}

    public static class Skill extends SkillProperties {}

    public static class ApiSecurity extends ApiSecurityProperties {}

    public static class Cors extends CorsProperties {}

    public static class Integrations extends IntegrationsProperties {}

    public static class Endpoint extends EndpointProperties {}
}
