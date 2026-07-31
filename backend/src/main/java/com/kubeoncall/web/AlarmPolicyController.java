package com.kubeoncall.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.policy.AlarmPolicyCompiler;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.web.dto.AlarmPolicyEvaluationResponse;
import com.kubeoncall.web.dto.AlarmPolicyReplayRequest;
import com.kubeoncall.web.dto.AlarmPolicyReplayResponse;
import com.kubeoncall.web.dto.AlarmPolicyRollbackRequest;
import com.kubeoncall.web.dto.AlarmRequest;

@LegacyApiController
@RestController
@RequestMapping("/api/alarm-policies")
public class AlarmPolicyController {

    private static final Logger log = LoggerFactory.getLogger(AlarmPolicyController.class);
    private final YamlAlarmPolicyRepository repository;
    private final AlarmNormalizer alarmNormalizer;
    private final AlarmPolicyEngine policyEngine;
    private final AlarmPolicyCompiler policyCompiler;
    private final ExecutionAuditService executionAuditService;

    @Autowired
    public AlarmPolicyController(
            YamlAlarmPolicyRepository repository,
            AlarmNormalizer alarmNormalizer,
            AlarmPolicyEngine policyEngine,
            AlarmPolicyCompiler policyCompiler,
            ExecutionAuditService executionAuditService) {
        this.repository = repository;
        this.alarmNormalizer = alarmNormalizer;
        this.policyEngine = policyEngine;
        this.policyCompiler = policyCompiler;
        this.executionAuditService = executionAuditService;
    }

    /** Retained for controller-focused tests and callers that do not inject a compiler explicitly. */
    public AlarmPolicyController(
            YamlAlarmPolicyRepository repository,
            AlarmNormalizer alarmNormalizer,
            AlarmPolicyEngine policyEngine,
            ExecutionAuditService executionAuditService) {
        this(repository, alarmNormalizer, policyEngine, new AlarmPolicyCompiler(repository), executionAuditService);
    }

    @PostMapping("/reload")
    public YamlAlarmPolicyRepository.ReloadResult reload() {
        Instant startedAt = Instant.now();
        YamlAlarmPolicyRepository.ReloadResult result = repository.reload();
        auditPolicy(
                "reload",
                "POLICY_RELOADED",
                "Alarm policies reloaded to version " + result.activeVersion(),
                null,
                startedAt,
                Map.of(
                        "previousVersion",
                        result.previousVersion(),
                        "activeVersion",
                        result.activeVersion(),
                        "policyCount",
                        result.policyCount()));
        return result;
    }

    @PostMapping("/rollback")
    public YamlAlarmPolicyRepository.PolicySnapshot rollback(@RequestBody AlarmPolicyRollbackRequest request) {
        if (request == null || request.version() == null || request.version().isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        Instant startedAt = Instant.now();
        YamlAlarmPolicyRepository.PolicySnapshot result = repository.rollback(request.version());
        auditPolicy(
                "rollback",
                "POLICY_ROLLED_BACK",
                "Alarm policy rolled back to version " + result.version(),
                null,
                startedAt,
                Map.of("previousVersion", result.previousVersion(), "activeVersion", result.version()));
        return result;
    }

    @PostMapping("/versions")
    public Map<String, YamlAlarmPolicyRepository.PolicySnapshot> versions() {
        return repository.versionHistory();
    }

    /** Produces a deterministic, checksummed Prometheus rule file without mutating deployed rules. */
    @PostMapping("/prometheus-rules/dry-run")
    public AlarmPolicyCompiler.CompiledPrometheusRules dryRunPrometheusRules() {
        return policyCompiler.compileActive();
    }

    @PostMapping("/dry-run")
    public AlarmPolicyEvaluationResponse dryRun(@RequestBody AlarmRequest request) {
        Instant startedAt = Instant.now();
        try {
            AlarmPolicyEvaluationResponse result = evaluate(request);
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("matched", result.matched());
            metadata.put("policyId", result.policyId());
            metadata.put("policyVersion", result.policyVersion());
            metadata.put("severity", result.severity());
            auditPolicy("dry_run", "POLICY_DRY_RUN", "Alarm policy dry-run completed", null, startedAt, metadata);
            return result;
        } catch (RuntimeException ex) {
            auditPolicy(
                    "dry_run",
                    "FAILED",
                    "Alarm policy dry-run failed",
                    "Alarm policy evaluation failed",
                    startedAt,
                    Map.of("errorType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    @PostMapping("/replay")
    public AlarmPolicyReplayResponse replay(@RequestBody AlarmPolicyReplayRequest request) {
        Instant startedAt = Instant.now();
        List<AlarmRequest> alarms = request == null || request.alarms() == null ? List.of() : request.alarms();
        if (alarms.size() > 1000) {
            auditPolicy(
                    "replay",
                    "FAILED",
                    "Alarm policy replay rejected",
                    "Replay accepts at most 1000 alarms",
                    startedAt,
                    Map.of("total", alarms.size()));
            throw new IllegalArgumentException("Replay accepts at most 1000 alarms");
        }
        List<AlarmPolicyEvaluationResponse> results = new ArrayList<>();
        int matched = 0;
        for (AlarmRequest alarm : alarms) {
            AlarmPolicyEvaluationResponse result = evaluate(alarm);
            results.add(result);
            if (result.matched()) {
                matched++;
            }
        }
        AlarmPolicyReplayResponse response = new AlarmPolicyReplayResponse(
                repository.activeVersion(), alarms.size(), matched, alarms.size() - matched, results);
        auditPolicy(
                "replay",
                "POLICY_REPLAYED",
                "Alarm policy replay completed",
                null,
                startedAt,
                Map.of(
                        "policyVersion",
                        repository.activeVersion(),
                        "total",
                        alarms.size(),
                        "matched",
                        matched,
                        "unmatched",
                        alarms.size() - matched));
        return response;
    }

    private void auditPolicy(
            String operation,
            String status,
            String summary,
            String failureReason,
            Instant startedAt,
            Map<String, Object> metadata) {
        try {
            executionAuditService.recordAlarmExecution(
                    "alarm-policy-" + operation + "-" + startedAt.toEpochMilli(),
                    status,
                    true,
                    false,
                    summary,
                    failureReason,
                    List.of("alarm.policy." + operation),
                    startedAt,
                    metadata);
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to audit alarm policy operation: operation={}, status={}, errorType={}",
                    operation,
                    status,
                    ex.getClass().getSimpleName());
        }
    }

    private AlarmPolicyEvaluationResponse evaluate(AlarmRequest request) {
        NormalizedAlarmEvent event = alarmNormalizer.normalize(request);
        AlarmEvaluationResult evaluation = policyEngine.evaluate(event);
        return new AlarmPolicyEvaluationResponse(
                event.alarmId(),
                event.fingerprint(),
                event.alertName(),
                evaluation.matched(),
                evaluation.policyId(),
                evaluation.matchedPolicy() == null
                        ? repository.activeVersion()
                        : evaluation.matchedPolicy().version(),
                evaluation.finalSeverity() == null
                        ? null
                        : evaluation.finalSeverity().name(),
                evaluation.workflowTemplate(),
                evaluation.runbookId(),
                evaluation.reason());
    }
}
