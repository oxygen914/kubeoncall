package com.kubeoncall.web;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.web.dto.AlarmPolicyReplayRequest;
import com.kubeoncall.web.dto.AlarmRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-policies")
public class AlarmPolicyController {

    private final YamlAlarmPolicyRepository repository;
    private final AlarmNormalizer alarmNormalizer;
    private final AlarmPolicyEngine policyEngine;
    private final ExecutionAuditService executionAuditService;

    public AlarmPolicyController(YamlAlarmPolicyRepository repository,
                                 AlarmNormalizer alarmNormalizer,
                                 AlarmPolicyEngine policyEngine,
                                 ExecutionAuditService executionAuditService) {
        this.repository = repository;
        this.alarmNormalizer = alarmNormalizer;
        this.policyEngine = policyEngine;
        this.executionAuditService = executionAuditService;
    }

    @PostMapping("/reload")
    public YamlAlarmPolicyRepository.ReloadResult reload() {
        Instant startedAt = Instant.now();
        YamlAlarmPolicyRepository.ReloadResult result = repository.reload();
        auditPolicy("reload", "POLICY_RELOADED",
                "Alarm policies reloaded to version " + result.activeVersion(), null, startedAt,
                Map.of("previousVersion", result.previousVersion(), "activeVersion", result.activeVersion(),
                        "policyCount", result.policyCount()));
        return result;
    }

    @PostMapping("/dry-run")
    public Map<String, Object> dryRun(@RequestBody AlarmRequest request) {
        Instant startedAt = Instant.now();
        try {
            Map<String, Object> result = evaluate(request);
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("matched", result.get("matched"));
            metadata.put("policyId", result.get("policyId"));
            metadata.put("policyVersion", result.get("policyVersion"));
            metadata.put("severity", result.get("severity"));
            auditPolicy("dry_run", "POLICY_DRY_RUN", "Alarm policy dry-run completed",
                    null, startedAt, metadata);
            return result;
        } catch (RuntimeException ex) {
            auditPolicy("dry_run", "FAILED", "Alarm policy dry-run failed",
                    ex.getMessage(), startedAt, Map.of("errorType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestBody AlarmPolicyReplayRequest request) {
        Instant startedAt = Instant.now();
        List<AlarmRequest> alarms = request == null || request.alarms() == null ? List.of() : request.alarms();
        if (alarms.size() > 1000) {
            auditPolicy("replay", "FAILED", "Alarm policy replay rejected",
                    "Replay accepts at most 1000 alarms", startedAt, Map.of("total", alarms.size()));
            throw new IllegalArgumentException("Replay accepts at most 1000 alarms");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int matched = 0;
        for (AlarmRequest alarm : alarms) {
            Map<String, Object> result = evaluate(alarm);
            results.add(result);
            if (Boolean.TRUE.equals(result.get("matched"))) {
                matched++;
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("policyVersion", repository.activeVersion());
        response.put("total", alarms.size());
        response.put("matched", matched);
        response.put("unmatched", alarms.size() - matched);
        response.put("results", results);
        auditPolicy("replay", "POLICY_REPLAYED", "Alarm policy replay completed", null, startedAt,
                Map.of("policyVersion", repository.activeVersion(), "total", alarms.size(),
                        "matched", matched, "unmatched", alarms.size() - matched));
        return response;
    }

    private void auditPolicy(String operation,
                             String status,
                             String summary,
                             String failureReason,
                             Instant startedAt,
                             Map<String, Object> metadata) {
        try {
            executionAuditService.recordAlarmExecution(
                    "alarm-policy-" + operation + "-" + startedAt.toEpochMilli(), status, true, false,
                    summary, failureReason, List.of("alarm.policy." + operation), startedAt, metadata);
        } catch (RuntimeException ignored) {
            // Policy evaluation/reload must not be changed by an audit backend outage.
        }
    }

    private Map<String, Object> evaluate(AlarmRequest request) {
        NormalizedAlarmEvent event = alarmNormalizer.normalize(request);
        AlarmEvaluationResult evaluation = policyEngine.evaluate(event);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alarmId", event.alarmId());
        result.put("fingerprint", event.fingerprint());
        result.put("alertName", event.alertName());
        result.put("matched", evaluation.matched());
        result.put("policyId", evaluation.policyId());
        result.put("policyVersion", evaluation.matchedPolicy() == null
                ? repository.activeVersion()
                : evaluation.matchedPolicy().version());
        result.put("severity", evaluation.finalSeverity() == null ? null : evaluation.finalSeverity().name());
        result.put("workflowTemplate", evaluation.workflowTemplate());
        result.put("runbookId", evaluation.runbookId());
        result.put("reason", evaluation.reason());
        return result;
    }
}
