package com.kubeoncall.web.api.v1.migration;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.migration.ActiveAlarmBackfillRunner;
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
 * and observe the migration without shell access. Backfill is synchronous here for the MVP; a
 * long-running batch should later move to the async task worker.
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
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final KubeOnCallProperties properties;
    private final V1Security security;

    public MigrationAdminController(
            RedisInventoryService inventoryService,
            ActiveAlarmBackfillRunner alarmBackfillRunner,
            com.kubeoncall.migration.ApprovalBackfillRunner approvalBackfillRunner,
            com.kubeoncall.migration.SkillStateBackfillRunner skillBackfillRunner,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            KubeOnCallProperties properties,
            V1Security security) {
        this.inventoryService = inventoryService;
        this.alarmBackfillRunner = alarmBackfillRunner;
        this.approvalBackfillRunner = approvalBackfillRunner;
        this.skillBackfillRunner = skillBackfillRunner;
        this.ledgerProvider = ledgerProvider;
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

    @PostMapping("/backfill/active-alarm")
    public ApiResponse<Map<String, Object>> backfillActiveAlarm(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun, HttpServletRequest request) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        ActiveAlarmBackfillRunner.BackfillResult result =
                alarmBackfillRunner.run(dryRun, RequestIdFilter.currentRequestId());
        return backfillResponse(result);
    }

    @PostMapping("/backfill/approval")
    public ApiResponse<Map<String, Object>> backfillApproval(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun, HttpServletRequest request) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        com.kubeoncall.migration.ApprovalBackfillRunner.BackfillResult result =
                approvalBackfillRunner.run(dryRun, RequestIdFilter.currentRequestId());
        return backfillResponse(result);
    }

    @PostMapping("/backfill/skill-state")
    public ApiResponse<Map<String, Object>> backfillSkillState(
            @RequestParam(name = "dry-run", required = false) Boolean dryRun, HttpServletRequest request) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
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
            @RequestParam(name = "domain", required = false) String domain) {
        security.requirePermission(PermissionCode.SYSTEM_MANAGE);
        MigrationLedgerRepository ledger = requireLedger();
        int clampedSize = Math.min(100, Math.max(1, size));
        MigrationLedgerRepository.DiffPage result = ledger.listDiffs(page, clampedSize, domain);
        return PageMeta.ListEnvelope.of(
                result.rows(), page, clampedSize, result.total(), RequestIdFilter.currentRequestId());
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
}
