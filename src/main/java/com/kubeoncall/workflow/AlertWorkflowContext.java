package com.kubeoncall.workflow;

import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AlertWorkflowContext {

    private final AlarmEvent alarmEvent;
    private final Instant startedAt;
    private final List<NodeResult> nodeResults = new ArrayList<>();
    private final List<String> failedNodes = new ArrayList<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private boolean degraded;
    private boolean terminated;

    public AlertWorkflowContext(AlarmEvent alarmEvent, Instant startedAt) {
        this.alarmEvent = alarmEvent;
        this.startedAt = startedAt;
    }

    public AlarmEvent getAlarmEvent() {
        return alarmEvent;
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
