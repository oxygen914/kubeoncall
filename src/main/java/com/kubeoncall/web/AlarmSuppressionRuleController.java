package com.kubeoncall.web;

import com.kubeoncall.alarm.suppression.AlarmSuppressionRuleRepository;
import com.kubeoncall.service.ExecutionAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-suppression-rules")
public class AlarmSuppressionRuleController {

    private final AlarmSuppressionRuleRepository repository;
    private final ExecutionAuditService auditService;

    public AlarmSuppressionRuleController(AlarmSuppressionRuleRepository repository,
                                          ExecutionAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("activeVersion", repository.activeVersion(), "rules", repository.findAll());
    }

    @PostMapping("/reload")
    public AlarmSuppressionRuleRepository.ReloadResult reload() {
        Instant startedAt = Instant.now();
        AlarmSuppressionRuleRepository.ReloadResult result = repository.reload();
        auditService.recordAlarmExecution(
                "alarm-suppression-reload-" + startedAt.toEpochMilli(), "SUPPRESSION_RULES_RELOADED",
                true, false, "Alarm suppression rules reloaded", null,
                List.of("alarm.suppression.reload"), startedAt,
                Map.of("previousVersion", result.previousVersion(), "activeVersion", result.activeVersion(),
                        "ruleCount", result.ruleCount()));
        return result;
    }
}
