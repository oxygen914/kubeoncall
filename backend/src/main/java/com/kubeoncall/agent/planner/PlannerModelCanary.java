package com.kubeoncall.agent.planner;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Executes a real, structured planner request during startup when explicitly enabled.
 *
 * <p>The canary validates provider connectivity, authentication, model availability and the
 * KubeOnCall planner response schema. No credentials, prompts or provider response bodies are
 * logged. Deployments may fail closed so an invalid model configuration never advertises a healthy
 * AI operations capability.
 */
@Component
@Order(30)
public class PlannerModelCanary implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlannerModelCanary.class);
    private static final String CANARY_REQUEST =
            "Planner readiness canary. Return a read-only QUERY_METRICS plan for target "
                    + "kubeoncall-planner-canary, use LOW risk, and request no skills.";

    private final PlannerLlmService planner;
    private final KubeOnCallProperties properties;

    public PlannerModelCanary(PlannerLlmService planner, KubeOnCallProperties properties) {
        this.planner = planner;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getAiOperations().isPlannerCanaryEnabled()) {
            return;
        }
        PlannerMode configured =
                PlannerMode.configured(properties.getAiOperations().getPlannerMode());
        if (configured != PlannerMode.REAL_MODEL && configured != PlannerMode.RULE_ASSISTED) {
            failOrWarn("planner mode is not model-backed", null);
            return;
        }

        PlannerLlmResult result = planner.planWithStatus(
                CANARY_REQUEST,
                Map.of(
                        "canary",
                        true,
                        "allowedTaskType",
                        "QUERY_METRICS",
                        "allowedRiskLevel",
                        "LOW",
                        "requestedSkills",
                        java.util.List.of()));
        if (result.degraded() || result.decision() == null) {
            failOrWarn(
                    "planner returned a degraded result",
                    result.degradedReason() == null
                            ? "UNKNOWN"
                            : result.degradedReason().name());
            return;
        }
        log.info(
                "Planner model canary passed: provider={}, model={}, mode={}, latencyMs={}",
                result.provider(),
                result.model(),
                result.mode(),
                result.latencyMs());
    }

    private void failOrWarn(String message, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "INVALID_CONFIGURATION" : reason;
        if (properties.getAiOperations().isPlannerCanaryFailFast()) {
            throw new IllegalStateException("Planner model canary failed: " + message + ", reason=" + safeReason);
        }
        log.warn("Planner model canary failed: {}, reason={}", message, safeReason);
    }
}
