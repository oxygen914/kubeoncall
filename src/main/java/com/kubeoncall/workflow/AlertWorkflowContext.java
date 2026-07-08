package com.kubeoncall.workflow;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable per-execution state for an alarm workflow run.
 *
 * <p>Carries both the legacy {@link AlarmEvent} (for backwards-compatible node access) and the
 * normalized event plus policy evaluation result produced by the new alarm governance layer. Nodes
 * that have been migrated read from {@link #getNormalizedAlarm()} / {@link #getEvaluationResult()};
 * nodes that have not yet been migrated continue to read from {@link #getAlarmEvent()}.
 */
public class AlertWorkflowContext {

    private final AlarmEvent alarmEvent;
    private final NormalizedAlarmEvent normalizedAlarm;
    private final AlarmEvaluationResult evaluationResult;
    private final Instant startedAt;
    private final List<NodeResult> nodeResults = new ArrayList<>();
    private final List<String> failedNodes = new ArrayList<>();
    private final List<String> skippedNodes = new ArrayList<>();
    private final Set<String> completedNodes = new LinkedHashSet<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private boolean degraded;
    private boolean terminated;

    /** Legacy entry point: no normalized event, no policy evaluation. */
    public AlertWorkflowContext(AlarmEvent alarmEvent, Instant startedAt) {
        this(alarmEvent, null, null, startedAt);
    }

    public AlertWorkflowContext(AlarmEvent alarmEvent,
                                NormalizedAlarmEvent normalizedAlarm,
                                AlarmEvaluationResult evaluationResult,
                                Instant startedAt) {
        this.alarmEvent = alarmEvent;
        this.normalizedAlarm = normalizedAlarm;
        this.evaluationResult = evaluationResult;
        this.startedAt = startedAt;
    }

    public AlarmEvent getAlarmEvent() {
        return alarmEvent;
    }

    public NormalizedAlarmEvent getNormalizedAlarm() {
        return normalizedAlarm;
    }

    public AlarmEvaluationResult getEvaluationResult() {
        return evaluationResult;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public List<NodeResult> getNodeResults() {
        return List.copyOf(nodeResults);
    }

    public void addNodeResult(NodeResult nodeResult) {
        this.nodeResults.add(nodeResult);
    }

    public List<String> getFailedNodes() {
        return List.copyOf(failedNodes);
    }

    public void addFailedNode(String nodeName) {
        this.failedNodes.add(nodeName);
    }

    public List<String> getSkippedNodes() {
        return List.copyOf(skippedNodes);
    }

    public void addSkippedNode(String nodeName) {
        this.skippedNodes.add(nodeName);
    }

    public Set<String> getCompletedNodes() {
        return Set.copyOf(completedNodes);
    }

    public void addCompletedNode(String nodeName) {
        this.completedNodes.add(nodeName);
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public boolean isTerminated() {
        return terminated;
    }

    public void terminate(String nodeName) {
        this.terminated = true;
        putAttribute("workflowTerminatedBy", nodeName);
    }

    public Map<String, Object> getAttributes() {
        return Map.copyOf(attributes);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void putAttribute(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
