package com.kubeoncall.agent.sandbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.agent.executor.ExecutorAgent;
import com.kubeoncall.agent.verifier.VerifierAgent;
import com.kubeoncall.approval.ApprovalService;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.sandbox.SandboxRunRecord;
import com.kubeoncall.sandbox.SandboxRunRepository;
import com.kubeoncall.service.AskService;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.runtime.WorkflowTaskResultCoordinator;

/** Persists terminal Sandbox evidence for later verifier review; it never resumes production execution. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SandboxWorkflowRecoveryTaskHandler implements AsyncTaskHandler {

    private final SandboxRunRepository runs;
    private final ApprovalService approvals;
    private final SandboxWorkflowRecoveryService recovery;
    private final SandboxProductionStateRecheckService productionRecheck;
    private final ExecutorAgent executorAgent;
    private final VerifierAgent verifierAgent;
    private final WorkflowTaskResultCoordinator coordinator;

    public SandboxWorkflowRecoveryTaskHandler(
            SandboxRunRepository runs,
            ApprovalService approvals,
            SandboxWorkflowRecoveryService recovery,
            SandboxProductionStateRecheckService productionRecheck,
            ExecutorAgent executorAgent,
            VerifierAgent verifierAgent,
            WorkflowTaskResultCoordinator coordinator) {
        this.runs = runs;
        this.approvals = approvals;
        this.recovery = recovery;
        this.productionRecheck = productionRecheck;
        this.executorAgent = executorAgent;
        this.verifierAgent = verifierAgent;
        this.coordinator = coordinator;
    }

    @Override
    public String taskType() {
        return SandboxTerminalOutboxHandler.TASK_TYPE;
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        context.requireValidLease();
        String runId = required(context.task().request(), "runId");
        String executionId = required(context.task().request(), "executionId");
        SandboxRunRecord run =
                runs.findByPublicId(runId).orElseThrow(() -> new IllegalArgumentException("Sandbox run not found"));
        if (!executionId.equals(run.executionPublicId()) || !run.isTerminal()) {
            throw new IllegalArgumentException("Sandbox terminal event does not match its execution");
        }
        WorkflowExecutionRecord execution = coordinator.markSandboxRecovering(context);
        if (!"RUNNING".equals(execution.status())) {
            return new HandlerResult(Map.of("accepted", false, "reason", "execution is not waiting for sandbox"));
        }
        GraphState state = approvals.loadState(executionId);
        SandboxWorkflowRecoveryService.Recovery result = recovery.recover(state, run);
        if (!result.accepted()) {
            return new HandlerResult(Map.of("accepted", false, "reason", result.reason()));
        }
        if (run.runStatus() != com.kubeoncall.sandbox.domain.SandboxRunStatus.SUCCEEDED) {
            state.setStatus(GraphStatus.FAILED);
            state.addObservation("Sandbox did not succeed; production execution remains blocked");
        } else if (!productionRecheck.recheck(state).current()) {
            state.setStatus(GraphStatus.FAILED);
            state.addObservation("Current production state recheck failed; production execution remains blocked");
        } else {
            executorAgent.plan(state);
            if (state.getStatus() == GraphStatus.SUCCESS) {
                verifierAgent.run(state);
            }
            if (state.getStatus() == GraphStatus.SUCCESS) {
                state.setStatus(GraphStatus.FAILED);
                state.addObservation("Verifier did not produce the required human approval gate");
            }
        }
        context.requireValidLease();
        approvals.saveState(state);
        WorkflowTaskResultCoordinator.FinalizationResult finalized =
                coordinator.finalizeResult(context, asAskResult(state), "sandbox-recovery");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", true);
        response.put("runId", runId);
        response.put("executionId", executionId);
        response.put("requiresVerifier", true);
        response.put("requiresProductionRecheck", true);
        response.put("workflowStatus", finalized.status());
        response.put("approvalId", finalized.approvalId());
        return new HandlerResult(response);
    }

    private static AskService.AskExecutionResult asAskResult(GraphState state) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nodeResults", List.copyOf(state.getNodeResults()));
        details.put("currentTask", state.getCurrentTask());
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("pause", state.getPauseMetadata());
        details.put("approval", approval);
        return new AskService.AskExecutionResult(
                state.getExecutionId(),
                state.getStatus().name(),
                "Sandbox recovery completed verifier gate",
                null,
                details);
    }

    private static String required(Map<String, Object> request, String key) {
        Object value = request.get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) throw new IllegalArgumentException("Sandbox recovery task is missing " + key);
        return text;
    }
}
