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

    private AlarmReadSource alarmReadSource = AlarmReadSource.MYSQL;
    private AlarmWriteMode alarmWriteMode = AlarmWriteMode.REDIS_PRIMARY;
    private int backfillBatchSize = 100;
    private long backfillScanLimit = 10000;
    private boolean backfillDryRun = true;

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

    public boolean isBackfillDryRun() {
        return backfillDryRun;
    }

    public void setBackfillDryRun(boolean backfillDryRun) {
        this.backfillDryRun = backfillDryRun;
    }
}
