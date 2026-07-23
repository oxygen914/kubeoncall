package com.kubeoncall.web.api.v1.migration;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.migration.ActiveAlarmBackfillRunner;
import com.kubeoncall.migration.LegacyAlarmCommandBackfillRunner;
import com.kubeoncall.migration.LegacyAlarmCommandPreflightService;
import com.kubeoncall.migration.LegacyExecutionAuditBackfillRunner;
import com.kubeoncall.migration.MigrationBackfillTaskHandler;
import com.kubeoncall.migration.MigrationBackfillTaskSubmissionService;
import com.kubeoncall.migration.MigrationLedgerRepository;
import com.kubeoncall.migration.RedisInventoryService;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/migration} — admin surface for the WBS-11 data cutover. Requires {@code system:manage}.
 * Exposes the Redis inventory, the ActiveAlarm backfill runner (with dry-run), the current
 * read-source/write-mode/legacy-api flag state, and paged batch/diff reports so operators can drive
 * and observe the migration without shell access. Apply requests must explicitly acknowledge the
 * destructive operation with {@code confirm-apply=true}; a long-running batch should later move
 * to the async task worker.
 *
 * <p>The ledger repository is injected via {@link ObjectProvider} because it is only created when
 * MySQL is enabled; the batch/diff endpoints return 503 when it is absent rather than failing startup.
 */
@RestController
@RequestMapping("/api/v1/migration")
public class MigrationAdminController {

    private final RedisInventoryService inventoryService;
    private final ActiveAlarmBackfillRunner alarmBackfillRunner;
    private final com.kubeoncall.migration.ApprovalBackfillRunner approvalBackfillRunner;
    private final com.kubeoncall.migration.SkillStateBackfillRunner skillBackfillRunner;
    private final LegacyAlarmCommandPreflightService legacyAlarmCommandPreflightService;
    private final LegacyAlarmCommandBackfillRunner legacyAlarmCommandBackfillRunner;
    private final LegacyExecutionAuditBackfillRunner legacyExecutionAuditBackfillRunner;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final ObjectProvider<MigrationBackfillTaskSubmissionService> taskSubmissionProvider;
    private final KubeOnCallProperties properties;
    private final V1Security security;

    public MigrationAdminController(
            RedisInventoryService inventoryService,
            ActiveAlarmBackfillRunner alarmBackfillRunner,
            com.kubeoncall.migration.ApprovalBackfillRunner approvalBackfillRunner,
            com.kubeoncall.migration.SkillStateBackfillRunner skillBackfillRunner,
            LegacyAlarmCommandPreflightService legacyAlarmCommandPreflightService,
            LegacyAlarmCommandBackfillRunner legacyAlarmCommandBackfillRunner,
            LegacyExecutionAuditBackfillRunner legacyExecutionAuditBackfillRunner,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            ObjectProvider<MigrationBackfillTaskSubmissionService> taskSubmissionProvider,
            KubeOnCallProperties properties,
            V1Security security) {
        this.inventoryService = inventoryService;
        this.alarmBackfillRunner = alarmBackfillRunner;
        this.approvalBackfillRunner = approvalBackfillRunner;
        this.skillBackfillRunner = skillBackfillRunner;
        this.legacyAlarmCommandPreflightService = legacyAlarmCommandPreflightService;
        this.legacyAlarmCommandBackfillRunner = legacyAlarmCommandBackfillRunner;
        this.legacyExecutionAuditBackfillRunner = legacyExecutionAuditBackfillRunner;
        this.ledgerProvider = ledgerProvider;
        this.taskSubmissionProvider = taskSubmissionProvider;
        this.properties = properties;
        this.security = security;
    }

    @GetMapping("/redis-inventory")
    public ApiResponse<Map<String, Object>> redisInventory() {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        RedisInventoryService.InventoryReport report = inventoryService.inventory();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanned", report.scanned());
        data.put("byCategory", report.byCategory());
        data.put("byPrefix", report.byPrefix());
        data.put("byTtlBucket", report.byTtlBucket());
        data.put("note", report.note());
        data.put("activeBusinessFactPrefixes", report.activeBusinessFactPrefixes());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    /** Lists exact identity/incident prerequisites before any legacy alarm-command apply is allowed. */
    @GetMapping("/preflight/legacy-alarm-commands")
    public ApiResponse<LegacyAlarmCommandPreflightService.PreflightReport> preflightLegacyAlarmCommands() {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        return ApiResponse.ok(legacyAlarmCommandPreflightService.inspect(), RequestIdFilter.currentRequestId());
    }

    /**
     * Queues a fenced durable backfill task. The seven historical synchronous endpoints remain
     * available only for compatibility; Console uses this endpoint so HTTP never owns a long scan.
     */
    @PostMapping("/backfill/tasks")
    public ApiResponse<MigrationTaskAccepted> submitBackfillTask(
            @RequestParam("domain") MigrationBackfillTaskHandler.Domain domain,
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        var task = taskSubmissionService().submit(domain, dryRun, RequestIdFilter.currentRequestId());
        return ApiResponse.ok(
                new MigrationTaskAccepted(task.publicId(), task.status(), domain.name(), effectiveDryRun(dryRun)),
                RequestIdFilter.currentRequestId());
    }

    @PostMapping("/backfill/alarm-acknowledgement")
    public ApiResponse<Map<String, Object>> backfillAlarmAcknowledgements(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        return backfillResponse(legacyAlarmCommandBackfillRunner.run(
                LegacyAlarmCommandBackfillRunner.Kind.ACKNOWLEDGEMENT, dryRun, RequestIdFilter.currentRequestId()));
    }

    @PostMapping("/backfill/alarm-silence")
    public ApiResponse<Map<String, Object>> backfillAlarmSilences(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        return backfillResponse(legacyAlarmCommandBackfillRunner.run(
                LegacyAlarmCommandBackfillRunner.Kind.SILENCE, dryRun, RequestIdFilter.currentRequestId()));
    }

    @PostMapping("/backfill/alarm-recovery")
    public ApiResponse<Map<String, Object>> backfillAlarmRecoveries(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        return backfillResponse(legacyAlarmCommandBackfillRunner.run(
                LegacyAlarmCommandBackfillRunner.Kind.RECOVERY, dryRun, RequestIdFilter.currentRequestId()));
    }

    @PostMapping("/backfill/execution-audit")
    public ApiResponse<Map<String, Object>> backfillExecutionAudit(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        return backfillResponse(legacyExecutionAuditBackfillRunner.run(dryRun, RequestIdFilter.currentRequestId()));
    }

    @PostMapping("/backfill/active-alarm")
    public ApiResponse<Map<String, Object>> backfillActiveAlarm(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply,
            HttpServletRequest request) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        ActiveAlarmBackfillRunner.BackfillResult result =
                alarmBackfillRunner.run(dryRun, RequestIdFilter.currentRequestId());
        return backfillResponse(result);
    }

    @PostMapping("/backfill/approval")
    public ApiResponse<Map<String, Object>> backfillApproval(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply,
            HttpServletRequest request) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        com.kubeoncall.migration.ApprovalBackfillRunner.BackfillResult result =
                approvalBackfillRunner.run(dryRun, RequestIdFilter.currentRequestId());
        return backfillResponse(result);
    }

    @PostMapping("/backfill/skill-state")
    public ApiResponse<Map<String, Object>> backfillSkillState(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun,
            @RequestParam(name = "confirm-apply", required = false) Boolean confirmApply,
            HttpServletRequest request) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        requireApplyConfirmation(dryRun, confirmApply);
        com.kubeoncall.migration.SkillStateBackfillRunner.BackfillResult result =
                skillBackfillRunner.run(dryRun, RequestIdFilter.currentRequestId());
        return backfillResponse(result);
    }

    private static ApiResponse<Map<String, Object>> backfillResponse(ActiveAlarmBackfillRunner.BackfillResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanned", result.scanned());
        data.put("migrated", result.migrated());
        data.put("skipped", result.skipped());
        data.put("failed", result.failed());
        data.put("checkpoint", result.checkpoint());
        data.put("dryRun", result.dryRun());
        data.put("note", result.note());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private static ApiResponse<Map<String, Object>> backfillResponse(
            com.kubeoncall.migration.ApprovalBackfillRunner.BackfillResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanned", result.scanned());
        data.put("migrated", result.migrated());
        data.put("skipped", result.skipped());
        data.put("failed", result.failed());
        data.put("checkpoint", result.checkpoint());
        data.put("dryRun", result.dryRun());
        data.put("note", result.note());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private static ApiResponse<Map<String, Object>> backfillResponse(
            com.kubeoncall.migration.SkillStateBackfillRunner.BackfillResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanned", result.scanned());
        data.put("migrated", result.migrated());
        data.put("skipped", result.skipped());
        data.put("failed", result.failed());
        data.put("checkpoint", result.checkpoint());
        data.put("dryRun", result.dryRun());
        data.put("note", result.note());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private static ApiResponse<Map<String, Object>> backfillResponse(
            LegacyAlarmCommandBackfillRunner.BackfillResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanned", result.scanned());
        data.put("migrated", result.migrated());
        data.put("skipped", result.skipped());
        data.put("failed", result.failed());
        data.put("checkpoint", result.checkpoint());
        data.put("dryRun", result.dryRun());
        data.put("note", result.note());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    private static ApiResponse<Map<String, Object>> backfillResponse(
            LegacyExecutionAuditBackfillRunner.BackfillResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanned", result.scanned());
        data.put("migrated", result.migrated());
        data.put("skipped", result.skipped());
        data.put("failed", result.failed());
        data.put("checkpoint", result.checkpoint());
        data.put("dryRun", result.dryRun());
        data.put("note", result.note());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    /**
     * A dry-run uses the explicit request value, or the configured default when omitted. Every
     * request whose effective mode writes facts must carry a separate confirmation flag so that a
     * caller cannot convert a previously safe dry-run URL into an apply by changing one parameter.
     */
    private void requireApplyConfirmation(Boolean dryRun, Boolean confirmApply) {
        boolean effectiveDryRun = effectiveDryRun(dryRun);
        if (!effectiveDryRun && !Boolean.TRUE.equals(confirmApply)) {
            throw new V1ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "Apply backfill requires confirm-apply=true after a successful dry-run");
        }
    }

    private boolean effectiveDryRun(Boolean dryRun) {
        return dryRun == null ? properties.getDataMigration().isBackfillDryRun() : dryRun;
    }

    private MigrationBackfillTaskSubmissionService taskSubmissionService() {
        MigrationBackfillTaskSubmissionService service = taskSubmissionProvider.getIfAvailable();
        if (service == null) {
            throw new V1ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Migration async task service is not available");
        }
        return service;
    }

    /** Current cutover flag state so operators can confirm the active read/write/legacy mode. */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(
                "alarmReadSource",
                properties.getDataMigration().getAlarmReadSource().name());
        data.put(
                "alarmWriteMode",
                properties.getDataMigration().getAlarmWriteMode().name());
        data.put("backfillDryRun", properties.getDataMigration().isBackfillDryRun());
        data.put("legacyApiEnabled", properties.getLegacyApi().isEnabled());
        data.put("ledgerAvailable", ledger != null && ledger.isAvailable());
        return ApiResponse.ok(data, RequestIdFilter.currentRequestId());
    }

    /** Paged migration batch history (newest first). */
    @GetMapping("/batches")
    public PageMeta.ListEnvelope<MigrationLedgerRepository.BatchSummary> batches(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "domain", required = false) String domain) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        MigrationLedgerRepository ledger = requireLedger();
        int clampedSize = Math.min(100, Math.max(1, size));
        MigrationLedgerRepository.BatchPage result = ledger.listBatches(page, clampedSize, domain);
        return PageMeta.ListEnvelope.of(
                result.rows(), page, clampedSize, result.total(), RequestIdFilter.currentRequestId());
    }

    /** Paged recorded diffs (newest first). Drives the cutover sign-off report. */
    @GetMapping("/diffs")
    public PageMeta.ListEnvelope<MigrationLedgerRepository.DiffSummary> diffs(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "resolution-status", required = false) String resolutionStatus) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        MigrationLedgerRepository ledger = requireLedger();
        int clampedSize = Math.min(100, Math.max(1, size));
        MigrationLedgerRepository.DiffPage result = ledger.listDiffs(page, clampedSize, domain, resolutionStatus);
        return PageMeta.ListEnvelope.of(
                result.rows(), page, clampedSize, result.total(), RequestIdFilter.currentRequestId());
    }

    /** Paged item-level migration evidence for failed/skipped-record diagnosis. */
    @GetMapping("/items")
    public PageMeta.ListEnvelope<MigrationLedgerRepository.ItemSummary> items(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "result", required = false) String result,
            @RequestParam(name = "batch-id", required = false) String batchId) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        int clampedSize = Math.min(100, Math.max(1, size));
        MigrationLedgerRepository.ItemPage pageResult =
                requireLedger().listItems(page, clampedSize, domain, result, batchId);
        return PageMeta.ListEnvelope.of(
                pageResult.rows(), page, clampedSize, pageResult.total(), RequestIdFilter.currentRequestId());
    }

    /** Bounded SHADOW comparison statistics, including the configured cutover threshold. */
    @GetMapping("/diff-statistics")
    public ApiResponse<MigrationLedgerRepository.DiffStatistics> diffStatistics(
            @RequestParam(name = "domain", defaultValue = "active-alarm") String domain,
            @RequestParam(name = "window-minutes", defaultValue = "60") int windowMinutes) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        int clampedWindowMinutes = Math.min(10080, Math.max(1, windowMinutes));
        MigrationLedgerRepository.DiffStatistics statistics = requireLedger()
                .diffStatistics(
                        domain,
                        clampedWindowMinutes,
                        properties.getDataMigration().getShadowDiffThresholdPercent());
        return ApiResponse.ok(statistics, RequestIdFilter.currentRequestId());
    }

    /** Records the operator's terminal disposition for one diff; it cannot be reopened through this API. */
    @PostMapping("/diffs/{diffId}/resolution")
    public ApiResponse<Map<String, Object>> resolveDiff(
            @PathVariable("diffId") String diffId,
            @RequestParam("status") String status,
            @RequestParam(name = "note", required = false) String note) {
        String operator =
                security.requirePermission(PermissionCode.SYSTEM_MANAGE).name();
        try {
            boolean resolved = requireLedger().resolveDiff(diffId, status, note, operator);
            if (!resolved) {
                throw V1ApiException.conflict(V1ApiErrorCode.CONFLICT, "Migration diff is absent or already resolved");
            }
        } catch (IllegalArgumentException ex) {
            throw V1ApiException.of(400, V1ApiErrorCode.INVALID_REQUEST, ex.getMessage());
        }
        return ApiResponse.ok(
                Map.of("publicId", diffId, "resolutionStatus", status.toUpperCase()),
                RequestIdFilter.currentRequestId());
    }

    private MigrationLedgerRepository requireLedger() {
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (ledger == null || !ledger.isAvailable()) {
            throw new V1ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Migration ledger is not available");
        }
        return ledger;
    }

    public record MigrationTaskAccepted(String taskId, String status, String domain, boolean dryRun) {}
}
