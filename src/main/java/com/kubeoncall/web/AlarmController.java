package com.kubeoncall.web;

import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.workflow.AlertWorkflowService;
import com.kubeoncall.web.dto.AlarmRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlertWorkflowService alertWorkflowService;

    public AlarmController(AlertWorkflowService alertWorkflowService) {
        this.alertWorkflowService = alertWorkflowService;
    }

    @PostMapping
    public List<NodeResult> ingest(@RequestBody AlarmRequest request) {
        AlarmEvent event = new AlarmEvent(
                request.alarmId(),
                request.dedupKey(),
                request.source(),
                request.severity(),
                request.nodeName(),
                request.summary(),
                request.occurredAt() == null ? Instant.now() : request.occurredAt(),
                request.metadata()
        );
        return alertWorkflowService.process(event);
    }
}
