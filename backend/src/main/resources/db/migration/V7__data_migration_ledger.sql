-- KubeOnCall data migration ledger (WBS-11).
-- Tracks the Redis → MySQL backfill so the migration is observable, repeatable and safe to
-- re-run. A batch is one invocation of a domain backfill runner; items are individual source keys
-- migrated within that batch; diffs are discrepancies found during shadow reads. None of these
-- tables hold business facts — they are migration bookkeeping and can be truncated after cutover.

CREATE TABLE koc_migration_batch (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id VARCHAR(40) NOT NULL,
  domain VARCHAR(64) NOT NULL,
  mode VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  scanned BIGINT UNSIGNED NOT NULL DEFAULT 0,
  migrated BIGINT UNSIGNED NOT NULL DEFAULT 0,
  skipped BIGINT UNSIGNED NOT NULL DEFAULT 0,
  failed BIGINT UNSIGNED NOT NULL DEFAULT 0,
  checkpoint VARCHAR(255) NULL,
  request_id VARCHAR(64) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_migration_batch_id (batch_id),
  KEY idx_migration_batch_domain_status (domain, status, started_at)
) ENGINE = InnoDB;

CREATE TABLE koc_migration_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id VARCHAR(40) NOT NULL,
  source_key VARCHAR(512) NOT NULL,
  domain VARCHAR(64) NOT NULL,
  source_checksum BINARY(32) NULL,
  target_public_id VARCHAR(40) NULL,
  result VARCHAR(32) NOT NULL,
  reason VARCHAR(1000) NULL,
  occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_migration_item_batch_key (batch_id, source_key),
  KEY idx_migration_item_domain_result (domain, result, occurred_at)
) ENGINE = InnoDB;

CREATE TABLE koc_migration_diff (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  domain VARCHAR(64) NOT NULL,
  resource_public_id VARCHAR(40) NULL,
  diff_type VARCHAR(64) NOT NULL,
  redis_summary VARCHAR(2000) NULL,
  mysql_summary VARCHAR(2000) NULL,
  request_id VARCHAR(64) NULL,
  occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_migration_diff_public_id (public_id),
  KEY idx_migration_diff_domain_resource (domain, resource_public_id, occurred_at)
) ENGINE = InnoDB;
