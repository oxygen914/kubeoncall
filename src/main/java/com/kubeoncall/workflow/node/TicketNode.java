package com.kubeoncall.workflow.node;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TicketNode implements AlertWorkflowNode {

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("fingerprint", context.getNormalizedAlarm() == null ? null : context.getNormalizedAlarm().fingerprint());
        ticket.put("severity", severity(context));
        ticket.put("summary", context.getAttribute("resultSummary"));
        ticket.put("status", "prepared");
        context.putAttribute("ticket", ticket);
        return new NodeResult("ticketNode", NodeStatus.SUCCESS, "Ticket payload prepared", ticket);
    }

    private String severity(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.finalSeverity() != null) {
            return evaluation.finalSeverity().name();
        }
        return context.getAlarmEvent().severity();
    }
}
