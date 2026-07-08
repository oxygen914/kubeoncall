package com.kubeoncall.web;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.workflow.AlertWorkflowService;
import com.kubeoncall.web.dto.AlarmRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmNormalizer alarmNormalizer;
    private final AlertWorkflowService alertWorkflowService;

    public AlarmController(AlarmNormalizer alarmNormalizer, AlertWorkflowService alertWorkflowService) {
        this.alarmNormalizer = alarmNormalizer;
        this.alertWorkflowService = alertWorkflowService;
    }

    @PostMapping
    public List<NodeResult> ingest(@RequestBody AlarmRequest request) {
        // Normalize both legacy and standard request shapes into one NormalizedAlarmEvent, then run
        // the governed pipeline (policy engine + fingerprint dedup + workflow).
        NormalizedAlarmEvent event = alarmNormalizer.normalize(request);
        return alertWorkflowService.process(event);
    }
}
