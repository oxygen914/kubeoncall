package com.kubeoncall.domain.graph;

import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskPlan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GraphState {

    private String executionId;
    private GraphStatus status = GraphStatus.RUNNING;
    private String userRequest;
    private TaskPlan taskPlan;
    private Task currentTask;
    private int currentLoop;
    private final List<String> observations = new ArrayList<>();
    private final List<NodeResult> nodeResults = new ArrayList<>();
    private final Map<String, Object> context = new HashMap<>();
    private PauseMetadata pauseMetadata;
    private int resumeAttempts;
    private ApprovalDecision finalApprovalDecision;
    private final List<String> approvalAuditTrail = new ArrayList<>();
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public GraphStatus getStatus() {
        return status;
    }

    public void setStatus(GraphStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getUserRequest() {
        return userRequest;
    }

    public void setUserRequest(String userRequest) {
        this.userRequest = userRequest;
    }

    public TaskPlan getTaskPlan() {
        return taskPlan;
    }

    public void setTaskPlan(TaskPlan taskPlan) {
        this.taskPlan = taskPlan;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public void setCurrentTask(Task currentTask) {
        this.currentTask = currentTask;
    }

    public int getCurrentLoop() {
        return currentLoop;
    }

    public void setCurrentLoop(int currentLoop) {
        this.currentLoop = currentLoop;
    }

    public List<String> getObservations() {
        return observations;
    }

    public List<NodeResult> getNodeResults() {
        return nodeResults;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public PauseMetadata getPauseMetadata() {
        return pauseMetadata;
    }

    public void setPauseMetadata(PauseMetadata pauseMetadata) {
        this.pauseMetadata = pauseMetadata;
        this.updatedAt = Instant.now();
    }

    public int getResumeAttempts() {
        return resumeAttempts;
    }

    public void setResumeAttempts(int resumeAttempts) {
        this.resumeAttempts = resumeAttempts;
        this.updatedAt = Instant.now();
    }

    public ApprovalDecision getFinalApprovalDecision() {
        return finalApprovalDecision;
    }

    public void setFinalApprovalDecision(ApprovalDecision finalApprovalDecision) {
        this.finalApprovalDecision = finalApprovalDecision;
        this.updatedAt = Instant.now();
    }

    public List<String> getApprovalAuditTrail() {
        return approvalAuditTrail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void addObservation(String observation) {
        this.observations.add(observation);
        this.updatedAt = Instant.now();
    }

    public void addNodeResult(NodeResult nodeResult) {
        this.nodeResults.add(nodeResult);
        this.updatedAt = Instant.now();
    }

    public void addApprovalAudit(String auditEvent) {
        this.approvalAuditTrail.add(auditEvent);
        this.updatedAt = Instant.now();
    }
}
