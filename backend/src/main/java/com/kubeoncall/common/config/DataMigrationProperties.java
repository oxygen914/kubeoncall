package com.kubeoncall.common.config;

/**
 * WBS-11 data-migration and cutover controls. These flags orchestrate the Redis → MySQL read-source
 * switch and the legacy API retirement, all behind explicit defaults so production stays on the
 * existing path until an operator opts in.
 *
 * <p>{@code alarmReadSource} selects where {@code /api/v1/alarms} detail reads from:
 * <ul>
 *   <li>{@code mysql} (default once cutover is trusted) — MySQL only.</li>
 *   <li>{@code redis} — Redis only (pre-cutover behaviour, list still MySQL-only).</li>
 *   <li>{@code shadow} — read MySQL, then also read Redis and record any diff.</li>
 * </ul>
 */
public class DataMigrationProperties {

    public enum AlarmReadSource {
        MYSQL,
        REDIS,
        SHADOW
    }

    /**
     * Controls which store is the authoritative write target for alarm state. The migration goes
     * {@code REDIS_PRIMARY} (Redis authoritative, MySQL shadow) → {@code DUAL_WRITE} (both, MySQL
     * verified) → {@code MYSQL_PRIMARY} (MySQL authoritative, Redis compatibility projection).
     * Flipping back to a previous mode is a config-only rollback — no data is deleted.
     */
    public enum AlarmWriteMode {
        REDIS_PRIMARY,
        DUAL_WRITE,
        MYSQL_PRIMARY
    }

    /**
     * Read source for the change-event correlation timeline. {@code REDIS} (default) keeps the
     * pre-cutover behaviour; {@code MYSQL} serves from {@code koc_change_event}; {@code SHADOW}
     * serves MySQL and records any Redis disagreement in the migration ledger.
     */
    public enum ChangeEventReadSource {
        REDIS,
        MYSQL,
        SHADOW
    }

    /**
     * Write target for recorded change events. Mirrors {@link AlarmWriteMode}:
     * {@code REDIS_PRIMARY} → Redis only; {@code DUAL_WRITE} → MySQL first then a best-effort Redis
     * compatibility projection; {@code MYSQL_PRIMARY} → MySQL only. Flipping back is a config-only
     * rollback.
     */
    public enum ChangeEventWriteMode {
        REDIS_PRIMARY,
        DUAL_WRITE,
        MYSQL_PRIMARY
    }

    private AlarmReadSource alarmReadSource = AlarmReadSource.MYSQL;
    private AlarmWriteMode alarmWriteMode = AlarmWriteMode.REDIS_PRIMARY;
    private ChangeEventReadSource changeEventReadSource = ChangeEventReadSource.REDIS;
    private ChangeEventWriteMode changeEventWriteMode = ChangeEventWriteMode.REDIS_PRIMARY;
    private int backfillBatchSize = 100;
    private long backfillScanLimit = 10000;
    private int backfillMaxItemsPerSecond = 100;
    private long backfillTaskTimeoutSeconds = 1800;
    private boolean backfillDryRun = true;
    private double shadowDiffThresholdPercent = 1.0d;
    private String legacyActorUsernameMappings = "";

    public AlarmReadSource getAlarmReadSource() {
        return alarmReadSource;
    }

    public void setAlarmReadSource(AlarmReadSource alarmReadSource) {
        this.alarmReadSource = alarmReadSource == null ? AlarmReadSource.MYSQL : alarmReadSource;
    }

    public AlarmWriteMode getAlarmWriteMode() {
        return alarmWriteMode;
    }

    public void setAlarmWriteMode(AlarmWriteMode alarmWriteMode) {
        this.alarmWriteMode = alarmWriteMode == null ? AlarmWriteMode.REDIS_PRIMARY : alarmWriteMode;
    }

    public ChangeEventReadSource getChangeEventReadSource() {
        return changeEventReadSource;
    }

    public void setChangeEventReadSource(ChangeEventReadSource changeEventReadSource) {
        this.changeEventReadSource =
                changeEventReadSource == null ? ChangeEventReadSource.REDIS : changeEventReadSource;
    }

    public ChangeEventWriteMode getChangeEventWriteMode() {
        return changeEventWriteMode;
    }

    public void setChangeEventWriteMode(ChangeEventWriteMode changeEventWriteMode) {
        this.changeEventWriteMode =
                changeEventWriteMode == null ? ChangeEventWriteMode.REDIS_PRIMARY : changeEventWriteMode;
    }

    public int getBackfillBatchSize() {
        return backfillBatchSize;
    }

    public void setBackfillBatchSize(int backfillBatchSize) {
        this.backfillBatchSize = backfillBatchSize;
    }

    public long getBackfillScanLimit() {
        return backfillScanLimit;
    }

    public void setBackfillScanLimit(long backfillScanLimit) {
        this.backfillScanLimit = backfillScanLimit;
    }

    /**
     * Maximum Redis source records a single application process may inspect per second while a
     * durable migration task is running. Zero disables throttling for emergency maintenance only.
     */
    public int getBackfillMaxItemsPerSecond() {
        return backfillMaxItemsPerSecond;
    }

    public void setBackfillMaxItemsPerSecond(int backfillMaxItemsPerSecond) {
        this.backfillMaxItemsPerSecond = backfillMaxItemsPerSecond;
    }

    /** Maximum wall-clock execution time for one durable migration task attempt. */
    public long getBackfillTaskTimeoutSeconds() {
        return backfillTaskTimeoutSeconds;
    }

    public void setBackfillTaskTimeoutSeconds(long backfillTaskTimeoutSeconds) {
        this.backfillTaskTimeoutSeconds = backfillTaskTimeoutSeconds;
    }

    public boolean isBackfillDryRun() {
        return backfillDryRun;
    }

    public void setBackfillDryRun(boolean backfillDryRun) {
        this.backfillDryRun = backfillDryRun;
    }

    /** Maximum SHADOW mismatch rate allowed during the queried sign-off window. */
    public double getShadowDiffThresholdPercent() {
        return shadowDiffThresholdPercent;
    }

    public void setShadowDiffThresholdPercent(double shadowDiffThresholdPercent) {
        this.shadowDiffThresholdPercent = Math.max(0d, shadowDiffThresholdPercent);
    }

    /**
     * Explicit legacy-actor to local-username mappings, encoded as comma-separated
     * {@code legacyActor=username} pairs. Values not listed here may only migrate when the legacy
     * actor already exactly matches a local username.
     */
    public String getLegacyActorUsernameMappings() {
        return legacyActorUsernameMappings;
    }

    public void setLegacyActorUsernameMappings(String legacyActorUsernameMappings) {
        this.legacyActorUsernameMappings = legacyActorUsernameMappings == null ? "" : legacyActorUsernameMappings;
    }
}
