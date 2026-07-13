package com.kubeoncall.workflow.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;

@Component
public class TicketNode implements AlertWorkflowNode {

    private final Map<String, ToolExecutor> executorsByKind;

    public TicketNode(List<ToolExecutor> toolExecutors) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put(
                "fingerprint",
                context.getNormalizedAlarm() == null
                        ? null
                        : context.getNormalizedAlarm().fingerprint());
        ticket.put("severity", severity(context));
        ticket.put("summary", context.getAttribute("resultSummary"));
        addPolicyContext(ticket, context);
        String action =
                isHighPriority(String.valueOf(ticket.get("severity"))) ? "createOrUpdateIncident" : "createTicket";
        ticket.put("executorKind", "incident");
        ticket.put("action", action);
        ToolExecutor incident = executorsByKind.get("incident");
        if (incident == null) {
            ticket.put("status", "failed");
            ticket.put("reason", "incident executor unavailable");
            context.putAttribute("ticket", ticket);
            return new NodeResult("ticketNode", NodeStatus.FAILURE, "Incident integration unavailable", ticket);
        }
        try {
            Map<String, Object> result = incident.execute(action, ticket);
            ticket.put("result", result);
            if (isFailure(result)) {
                ticket.put("status", "failed");
                context.putAttribute("ticket", ticket);
                return new NodeResult("ticketNode", NodeStatus.FAILURE, "Failed to create incident or ticket", ticket);
            }
            ticket.put("status", "created");
            context.putAttribute("ticket", ticket);
            return new NodeResult(
                    "ticketNode",
                    NodeStatus.SUCCESS,
                    "createOrUpdateIncident".equals(action)
                            ? "Incident created or updated"
                            : "Ticket created or updated",
                    ticket);
        } catch (RuntimeException ex) {
            ticket.put("status", "failed");
            ticket.put("exceptionType", ex.getClass().getSimpleName());
            ticket.put("errorMessage", ex.getMessage());
            context.putAttribute("ticket", ticket);
            return new NodeResult(
                    "ticketNode",
                    NodeStatus.FAILURE,
                    "Failed to create incident or ticket: " + ex.getMessage(),
                    ticket);
        }
    }

    private void addPolicyContext(Map<String, Object> ticket, AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null) {
            ticket.put("policyId", evaluation.policyId());
            ticket.put("runbookId", evaluation.runbookId());
            if (evaluation.matchedPolicy() != null) {
                ticket.put("owner", evaluation.matchedPolicy().owner());
            }
        }
        if (context.getNormalizedAlarm() != null) {
            ticket.put("service", context.getNormalizedAlarm().service());
            ticket.put("resourceName", context.getNormalizedAlarm().resourceName());
        }
    }

    private boolean isHighPriority(String severity) {
        return "P0".equalsIgnoreCase(severity) || "P1".equalsIgnoreCase(severity);
    }

    private boolean isFailure(Map<String, Object> result) {
        if (result == null) {
            return true;
        }
        Object status = result.get("status");
        Object httpStatus = result.get("httpStatus");
        return "failed".equalsIgnoreCase(String.valueOf(status))
                || httpStatus instanceof Number number && number.intValue() >= 400;
    }

    private String severity(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.finalSeverity() != null) {
            return evaluation.finalSeverity().name();
        }
        return context.getAlarmEvent().severity();
    }
}
