package com.kubeoncall.agent.base;

import com.kubeoncall.agent.node.AgentNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class ReActAgent extends AbstractAgent {

    protected abstract List<AgentNode> loopNodes();

    protected abstract int maxLoops();

    @Override
    public GraphState run(GraphState state) {
        return runLoop(state, loopNodes(), getAgentName());
    }

    protected GraphState runLoop(GraphState state, List<AgentNode> nodes, String retryGuardName) {
        int loops = 0;
        while (loops < maxLoops()) {
            boolean retry = false;
            for (AgentNode node : nodes) {
                NodeResult result = node.execute(state);
                state.addNodeResult(result);
                if (result.status() == NodeStatus.RETRY) {
                    state.addObservation(buildRetryObservation(node.getName(), result, loops));
                    recordRetryTrace(state, node.getName(), result, loops);
                    retry = true;
                    break;
                }
                if (result.status() == NodeStatus.FAILURE) {
                    state.setStatus(GraphStatus.FAILED);
                    return state;
                }
                if (result.status() == NodeStatus.WAITING) {
                    state.setStatus(GraphStatus.PAUSED);
                    return state;
                }
            }
            if (!retry) {
                state.setStatus(GraphStatus.SUCCESS);
                return state;
            }
            loops++;
            state.setCurrentLoop(loops);
        }

        state.setStatus(GraphStatus.REPLAN_REQUIRED);
        state.addNodeResult(NodeResult.retry(
                retryGuardName + "RetryGuard",
                "Retry budget exceeded for agent " + retryGuardName,
                "RETRY_BUDGET_EXCEEDED",
                "ESCALATE_TO_REPLAN",
                Map.of("maxLoops", maxLoops())
        ));
        return state;
    }

    private String buildRetryObservation(String nodeName, NodeResult result, int loop) {
        String reason = result.retryReason();
        String strategy = result.retryStrategy();
        return "Retry requested by " + nodeName
                + " at loop=" + loop
                + ", reason=" + (reason == null ? "unspecified" : reason)
                + ", strategy=" + (strategy == null ? "generic_retry" : strategy)
                + ", message=" + result.message();
    }

    @SuppressWarnings("unchecked")
    private void recordRetryTrace(GraphState state, String nodeName, NodeResult result, int loop) {
        Object trace = state.getContext().get("retryTrace");
        List<Map<String, Object>> entries;
        if (trace instanceof List<?> list) {
            entries = (List<Map<String, Object>>) list;
        } else {
            entries = new java.util.ArrayList<>();
            state.getContext().put("retryTrace", entries);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("node", nodeName);
        entry.put("loop", loop);
        entry.put("reason", result.retryReason());
        entry.put("strategy", result.retryStrategy());
        entry.put("message", result.message());
        entries.add(entry);
    }
}
