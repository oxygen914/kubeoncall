package com.kubeoncall.web.api.v1.operations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindow;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindowService;
import com.kubeoncall.alarm.policy.AlarmPolicyCompiler;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.alarm.suppression.AlarmSuppressionRuleRepository;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;
import com.kubeoncall.web.dto.AlarmPolicyEvaluationResponse;
import com.kubeoncall.web.dto.AlarmPolicyReplayRequest;
import com.kubeoncall.web.dto.AlarmPolicyReplayResponse;
import com.kubeoncall.web.dto.AlarmPolicyRollbackRequest;
import com.kubeoncall.web.dto.AlarmRequest;

/**
 * Versioned operational surface for alarm policies, maintenance windows and suppression rules.
 * Domain services remain shared with the legacy endpoints while authorization and audit use the v1
 * identity model.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {

    private final YamlAlarmPolicyRepository policyRepository;
    private final AlarmPolicyCompiler policyCompiler;
    private final AlarmPolicyEngine policyEngine;
    private final AlarmNormalizer alarmNormalizer;
    private final AlarmMaintenanceWindowService maintenanceService;
    private final AlarmSuppressionRuleRepository suppressionRepository;
    private final OperationAuditWriter auditWriter;
    private final V1Security security;

    public OperationsController(
            YamlAlarmPolicyRepository policyRepository,
            AlarmPolicyCompiler policyCompiler,
            AlarmPolicyEngine policyEngine,
            AlarmNormalizer alarmNormalizer,
            AlarmMaintenanceWindowService maintenanceService,
            AlarmSuppressionRuleRepository suppressionRepository,
            OperationAuditWriter auditWriter,
            V1Security security) {
        this.policyRepository = policyRepository;
        this.policyCompiler = policyCompiler;
        this.policyEngine = policyEngine;
        this.alarmNormalizer = alarmNormalizer;
        this.maintenanceService = maintenanceService;
        this.suppressionRepository = suppressionRepository;
        this.auditWriter = auditWriter;
        this.security = security;
    }

    @GetMapping("/policies")
    public ApiResponse<PolicyCatalogView> policies() {
        security.requirePermission(PermissionCode.POLICY_READ);
        List<PolicyVersionView> versions = policyRepository.versionHistory().values().stream()
                .map(snapshot -> new PolicyVersionView(
                        snapshot.version(),
                        snapshot.previousVersion(),
                        snapshot.loadedAt(),
                        snapshot.policies().size()))
                .sorted(java.util.Comparator.comparing(PolicyVersionView::loadedAt)
                        .reversed())
                .toList();
        return ApiResponse.ok(
                new PolicyCatalogView(policyRepository.activeVersion(), policyRepository.findAll(), versions),
                requestId());
    }

    @PostMapping("/policies/reload")
    public ApiResponse<YamlAlarmPolicyRepository.ReloadResult> reloadPolicies() {
        V1Principal actor = security.requirePermission(PermissionCode.POLICY_MANAGE);
        YamlAlarmPolicyRepository.ReloadResult result = policyRepository.reload();
        audit(
                actor,
                "policy.reload",
                "alarm-policy",
                Map.of("previousVersion", result.previousVersion()),
                Map.of("activeVersion", result.activeVersion(), "policyCount", result.policyCount()));
        return ApiResponse.ok(result, requestId());
    }

    @PostMapping("/policies/rollback")
    public ApiResponse<YamlAlarmPolicyRepository.PolicySnapshot> rollbackPolicy(
            @RequestBody AlarmPolicyRollbackRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.POLICY_MANAGE);
        if (request == null || request.version() == null || request.version().isBlank()) {
            throw invalidRequest("version is required");
        }
        String previous = policyRepository.activeVersion();
        YamlAlarmPolicyRepository.PolicySnapshot result = policyRepository.rollback(request.version());
        audit(
                actor,
                "policy.rollback",
                "alarm-policy",
                Map.of("activeVersion", previous),
                Map.of("activeVersion", result.version()));
        return ApiResponse.ok(result, requestId());
    }

    @PostMapping("/policies/dry-run")
    public ApiResponse<AlarmPolicyEvaluationResponse> dryRun(@RequestBody AlarmRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.POLICY_MANAGE);
        AlarmPolicyEvaluationResponse result = evaluate(request);
        audit(
                actor,
                "policy.dry-run",
                result.alarmId(),
                Map.of(),
                nullableMap(
                        "matched", result.matched(),
                        "policyId", result.policyId(),
                        "policyVersion", result.policyVersion()));
        return ApiResponse.ok(result, requestId());
    }

    @PostMapping("/policies/replay")
    public ApiResponse<AlarmPolicyReplayResponse> replay(@RequestBody AlarmPolicyReplayRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.POLICY_MANAGE);
        List<AlarmRequest> alarms = request == null || request.alarms() == null ? List.of() : request.alarms();
        if (alarms.size() > 1000) {
            throw invalidRequest("Replay accepts at most 1000 alarms");
        }
        List<AlarmPolicyEvaluationResponse> results =
                alarms.stream().map(this::evaluate).toList();
        int matched = (int)
                results.stream().filter(AlarmPolicyEvaluationResponse::matched).count();
        AlarmPolicyReplayResponse response = new AlarmPolicyReplayResponse(
                policyRepository.activeVersion(), alarms.size(), matched, alarms.size() - matched, results);
        audit(
                actor,
                "policy.replay",
                "alarm-policy",
                Map.of(),
                Map.of("total", alarms.size(), "matched", matched, "unmatched", alarms.size() - matched));
        return ApiResponse.ok(response, requestId());
    }

    @PostMapping("/policies/prometheus-rules/dry-run")
    public ApiResponse<AlarmPolicyCompiler.CompiledPrometheusRules> compilePrometheusRules() {
        V1Principal actor = security.requirePermission(PermissionCode.POLICY_MANAGE);
        AlarmPolicyCompiler.CompiledPrometheusRules result = policyCompiler.compileActive();
        audit(
                actor,
                "policy.prometheus-rules.dry-run",
                "alarm-policy",
                Map.of(),
                Map.of(
                        "policyVersion", result.policyVersion(),
                        "checksum", result.checksum(),
                        "ruleCount", result.ruleCount()));
        return ApiResponse.ok(result, requestId());
    }

    @GetMapping("/maintenance-windows")
    public ApiResponse<List<AlarmMaintenanceWindow>> maintenanceWindows() {
        security.requirePermission(PermissionCode.MAINTENANCE_READ);
        return ApiResponse.ok(maintenanceService.listCurrentAndUpcoming(), requestId());
    }

    @PostMapping("/maintenance-windows")
    public ApiResponse<AlarmMaintenanceWindow> createMaintenanceWindow(@RequestBody MaintenanceWindowRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.MAINTENANCE_MANAGE);
        if (request == null) {
            throw invalidRequest("request body is required");
        }
        AlarmMaintenanceWindow window = maintenanceService.create(
                request.startsAt(),
                request.endsAt(),
                request.matchers(),
                request.reason(),
                actor.user().username(),
                request.approvedBy(),
                request.approvalReference());
        audit(actor, "maintenance.create", window.id(), Map.of(), view(window));
        return ApiResponse.ok(window, requestId());
    }

    @DeleteMapping("/maintenance-windows/{windowId}")
    public ApiResponse<Map<String, Object>> revokeMaintenanceWindow(@PathVariable String windowId) {
        V1Principal actor = security.requirePermission(PermissionCode.MAINTENANCE_MANAGE);
        if (!maintenanceService.revoke(windowId)) {
            throw V1ApiException.notFound("Maintenance window not found: " + windowId);
        }
        Map<String, Object> result = Map.of("id", windowId, "revoked", true);
        audit(actor, "maintenance.revoke", windowId, Map.of(), result);
        return ApiResponse.ok(result, requestId());
    }

    @GetMapping("/suppression-rules")
    public ApiResponse<SuppressionCatalogView> suppressionRules() {
        security.requirePermission(PermissionCode.MAINTENANCE_READ);
        return ApiResponse.ok(
                new SuppressionCatalogView(suppressionRepository.activeVersion(), suppressionRepository.findAll()),
                requestId());
    }

    @PostMapping("/suppression-rules/reload")
    public ApiResponse<AlarmSuppressionRuleRepository.ReloadResult> reloadSuppressionRules() {
        V1Principal actor = security.requirePermission(PermissionCode.MAINTENANCE_MANAGE);
        AlarmSuppressionRuleRepository.ReloadResult result = suppressionRepository.reload();
        audit(
                actor,
                "suppression.reload",
                "alarm-suppression",
                Map.of("previousVersion", result.previousVersion()),
                Map.of("activeVersion", result.activeVersion(), "ruleCount", result.ruleCount()));
        return ApiResponse.ok(result, requestId());
    }

    private AlarmPolicyEvaluationResponse evaluate(AlarmRequest request) {
        if (request == null) {
            throw invalidRequest("alarm request is required");
        }
        NormalizedAlarmEvent event = alarmNormalizer.normalize(request);
        AlarmEvaluationResult evaluation = policyEngine.evaluate(event);
        return new AlarmPolicyEvaluationResponse(
                event.alarmId(),
                event.fingerprint(),
                event.alertName(),
                evaluation.matched(),
                evaluation.policyId(),
                evaluation.matchedPolicy() == null
                        ? policyRepository.activeVersion()
                        : evaluation.matchedPolicy().version(),
                evaluation.finalSeverity() == null
                        ? null
                        : evaluation.finalSeverity().name(),
                evaluation.workflowTemplate(),
                evaluation.runbookId(),
                evaluation.reason());
    }

    private void audit(
            V1Principal actor,
            String action,
            String resourceId,
            Map<String, Object> before,
            Map<String, Object> after) {
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", actor.user().id(), actor.user().displayName())
                .action(action)
                .resource("operations", resourceId)
                .before(before)
                .after(after)
                .requestId(requestId())
                .build());
    }

    private static Map<String, Object> view(AlarmMaintenanceWindow window) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", window.id());
        view.put("startsAt", window.startsAt());
        view.put("endsAt", window.endsAt());
        view.put("matchers", window.matchers());
        view.put("reason", window.reason());
        view.put("approvedBy", window.approvedBy());
        return view;
    }

    private static Map<String, Object> nullableMap(
            String key1, Object value1, String key2, Object value2, String key3, Object value3) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        values.put(key3, value3);
        return values;
    }

    private static String requestId() {
        return RequestIdFilter.currentRequestId();
    }

    private static V1ApiException invalidRequest(String message) {
        return V1ApiException.of(
                org.springframework.http.HttpStatus.BAD_REQUEST.value(),
                com.kubeoncall.web.api.v1.V1ApiErrorCode.INVALID_REQUEST,
                message);
    }

    public record PolicyCatalogView(
            String activeVersion,
            List<com.kubeoncall.alarm.domain.AlarmPolicy> policies,
            List<PolicyVersionView> versions) {}

    public record PolicyVersionView(String version, String previousVersion, Instant loadedAt, int policyCount) {}

    public record MaintenanceWindowRequest(
            Instant startsAt,
            Instant endsAt,
            Map<String, String> matchers,
            String reason,
            String approvedBy,
            String approvalReference) {}

    public record SuppressionCatalogView(
            String activeVersion, List<com.kubeoncall.alarm.suppression.AlarmSuppressionRule> rules) {}
}
