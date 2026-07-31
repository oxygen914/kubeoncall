-- WBS-11: retain operator resolution evidence and aggregate SHADOW comparisons without
-- persisting one audit row for every successful request.
ALTER TABLE koc_migration_diff
    ADD COLUMN resolution_status VARCHAR(16) NOT NULL DEFAULT 'OPEN' AFTER request_id,
    ADD COLUMN resolution_note VARCHAR(1000) NULL AFTER resolution_status,
    ADD COLUMN resolved_by VARCHAR(64) NULL AFTER resolution_note,
    ADD COLUMN resolved_at DATETIME(6) NULL AFTER resolved_by,
    ADD KEY idx_migration_diff_domain_resolution (domain, resolution_status, occurred_at),
    ADD CONSTRAINT chk_migration_diff_resolution_status
        CHECK (resolution_status IN ('OPEN', 'EXPLAINED', 'RESOLVED', 'ACCEPTED_RISK'));

CREATE TABLE koc_migration_shadow_observation (
    domain VARCHAR(64) NOT NULL,
    observed_minute DATETIME NOT NULL,
    comparison_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    mismatch_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (domain, observed_minute),
    CONSTRAINT chk_migration_shadow_observation_counts
        CHECK (mismatch_count <= comparison_count)
) ENGINE=InnoDB;
