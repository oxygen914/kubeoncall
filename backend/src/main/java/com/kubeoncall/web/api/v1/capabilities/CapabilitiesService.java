package com.kubeoncall.web.api.v1.capabilities;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.common.config.KubeOnCallProperties.Sandbox;

/**
 * Builds the deployment capability snapshot. Reads feature toggles from {@link KubeOnCallProperties}
 * rather than hardcoding them, so the frontend never guesses whether RAG, memory, change events or
 * SSE are wired up. Secrets and internal endpoints are deliberately excluded.
 */
@Service
public class CapabilitiesService {

    private final KubeOnCallProperties properties;
    private final String version;
    private final String commit;
    private final String environment;
    private final int defaultPageSize;
    private final int maxPageSize;
    private final long maxUploadBytes;
    private final int maxJsonlLines;

    public CapabilitiesService(
            KubeOnCallProperties properties,
            @Value("${kubeoncall.release.version:0.1.0}") String version,
            @Value("${kubeoncall.release.commit:unknown}") String commit,
            @Value("${kubeoncall.release.environment:local}") String environment,
            @Value("${kubeoncall.api.default-page-size:20}") int defaultPageSize,
            @Value("${kubeoncall.api.max-page-size:100}") int maxPageSize,
            @Value("${kubeoncall.api.max-upload-bytes:52428800}") long maxUploadBytes,
            @Value("${kubeoncall.api.max-jsonl-lines:100000}") int maxJsonlLines) {
        this.properties = properties;
        this.version = version;
        this.commit = commit;
        this.environment = environment;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
        this.maxUploadBytes = maxUploadBytes;
        this.maxJsonlLines = maxJsonlLines;
        // Fail fast at startup when the sandbox is enabled with misconfigured ceilings, rather than
        // silently clamping or rejecting a production Run later. When disabled the defaults are inert.
        if (properties.getSandbox().isEnabled()) {
            properties.getSandbox().validate();
        }
    }

    public Map<String, Object> release() {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("version", version);
        release.put("commit", commit);
        release.put("environment", environment);
        return release;
    }

    public Map<String, Object> features() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("rag", properties.getRag() != null);
        features.put("memory", properties.getMemory().isEnabled());
        features.put("skill", properties.getSkill().isEnabled());
        features.put("mcp", properties.getMcp().isEnabled());
        features.put("alarm", properties.getAlarm().isEnabled());
        features.put("changeEvents", properties.getChangeEvents().isWebhookEnabled());
        features.put("approval", properties.getApproval().isEnabled());
        features.put("policyManagement", true);
        features.put("sse", false);
        features.put("fileUpload", true);
        // Per-domain fact source for the non-isomorphic Redis/MySQL domains (WBS-11 GAP-11-02), so
        // integrators can tell whether a domain still serves from Redis or has cut over to MySQL.
        features.put(
                "approvalFactSource",
                properties.getDataMigration().getApproval().factSource());
        features.put(
                "skillStateFactSource",
                properties.getDataMigration().getSkillState().factSource());
        features.put(
                "executionAuditFactSource",
                properties.getDataMigration().getExecutionAudit().factSource());
        features.put("sandbox", sandboxFeatures());
        return features;
    }

    public Map<String, Object> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("defaultPageSize", defaultPageSize);
        limits.put("maxPageSize", maxPageSize);
        limits.put("maxUploadBytes", maxUploadBytes);
        limits.put("maxJsonlLines", maxJsonlLines);
        // Only expose sandbox limits when the capability is wired on; secrets and the internal
        // controller endpoint are never included.
        if (properties.getSandbox().isEnabled()) {
            limits.put("sandbox", properties.getSandbox().limits());
        }
        return limits;
    }

    public Map<String, Object> auth() {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("passwordLogin", true);
        auth.put("oidcLogin", false);
        auth.put("apiTokens", true);
        auth.put("sessionCookie", true);
        return auth;
    }

    /**
     * Sandbox capability surface: the master switch, the four run-mode toggles and the agent
     * auto-route flag. Per-mode toggles are reported verbatim so the frontend can show exactly what
     * the deployment supports; the master switch still gates whether any mode is actually usable.
     */
    private Map<String, Object> sandboxFeatures() {
        Sandbox sandbox = properties.getSandbox();
        Map<String, Object> sandboxFeatures = new LinkedHashMap<>();
        sandboxFeatures.put("enabled", sandbox.isEnabled());
        sandboxFeatures.put("fixedDiagnostic", sandbox.isFixedDiagnostic());
        sandboxFeatures.put("generatedCode", sandbox.isGeneratedCode());
        sandboxFeatures.put("manifestValidation", sandbox.isManifestValidation());
        sandboxFeatures.put("remediationSimulation", sandbox.isRemediationSimulation());
        sandboxFeatures.put("agentAutoRouteEnabled", sandbox.isAgentAutoRouteEnabled());
        return sandboxFeatures;
    }

    public Map<String, Object> links() {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("documentation", "/docs");
        links.put("support", null);
        return links;
    }
}
