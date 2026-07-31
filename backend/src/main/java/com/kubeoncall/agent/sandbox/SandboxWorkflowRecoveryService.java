package com.kubeoncall.agent.sandbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.sandbox.SandboxRunRecord;

/**
 * Converts a terminal Sandbox Run into verifier input only. This service deliberately has no
 * ToolExecutor, approval mutation or production client dependency, so a Sandbox result can never
 * become a production action by crossing this boundary.
 */
@Service
public class SandboxWorkflowRecoveryService {

    public Recovery recover(GraphState state, SandboxRunRecord run) {
        if (state == null || run == null || !run.isTerminal()) {
            throw new IllegalArgumentException("a paused graph state and terminal sandbox run are required");
        }
        Object routeValue = state.getContext().get("sandboxRoute");
        if (!(routeValue instanceof Map<?, ?> route) || !run.publicId().equals(String.valueOf(route.get("runId")))) {
            return Recovery.ignored("sandbox run does not match the paused workflow route");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("classification", "UNTRUSTED");
        evidence.put("runId", run.publicId());
        evidence.put("mode", run.mode().name());
        evidence.put("status", run.runStatus().name());
        evidence.put("errorCode", run.errorCode());
        evidence.put("errorSummary", run.errorSummary());
        evidence.put("finishedAt", run.finishedAt());
        evidence.put("recordedAt", Instant.now());
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("source", "sandbox");
        proposal.put("trusted", false);
        proposal.put("requiresProductionRecheck", true);
        proposal.put("requiresVerifier", true);
        proposal.put("requiresApprovalForMutation", true);
        proposal.put("suggestedAction", "NONE");
        proposal.put("preconditions", List.of("re-query current production state", "verify target scope"));
        proposal.put("stopConditions", List.of("state differs from evidence", "verifier rejects proposal"));
        proposal.put("rollbackAdvice", "Use the existing approved production rollback procedure.");
        state.getContext().put("sandboxEvidence", evidence);
        state.getContext().put("sandboxRemediationProposal", proposal);
        state.addObservation("Sandbox terminal result recorded as untrusted evidence runId=" + run.publicId());
        return Recovery.accepted(evidence, proposal);
    }

    public record Recovery(
            boolean accepted, String reason, Map<String, Object> evidence, Map<String, Object> proposal) {
        static Recovery accepted(Map<String, Object> evidence, Map<String, Object> proposal) {
            return new Recovery(
                    true,
                    "recorded",
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(evidence)),
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(proposal)));
        }

        static Recovery ignored(String reason) {
            return new Recovery(false, reason, Map.of(), Map.of());
        }
    }
}
