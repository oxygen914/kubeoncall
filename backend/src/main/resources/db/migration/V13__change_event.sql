-- KubeOnCall change-event fact table (WBS-11 GAP-11-01).
-- Scope: promote ChangeEvent from a Redis-only ZSET (alarm-change-events:timeline) into a
-- permanent MySQL fact, so change correlation can be served from MySQL after the cutover and the
-- 30-day Redis projection can be retired later (GAP-11-02). This is an Expand/Contract addition:
-- the table is created empty, backfilled from Redis, and only then does ChangeCorrelationService
-- switch its read source — no existing path is touched at migration time.

CREATE TABLE koc_change_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  change_id VARCHAR(255) NOT NULL,
  change_type VARCHAR(64) NOT NULL,
  changed_by VARCHAR(255) NULL,
  occurred_at DATETIME(6) NOT NULL,
  resource_type VARCHAR(64) NULL,
  resource_name VARCHAR(255) NULL,
  namespace VARCHAR(255) NULL,
  cluster VARCHAR(255) NULL,
  diff_payload JSON NULL,
  change_source VARCHAR(64) NULL,
  correlation_id VARCHAR(255) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_change_event_public_id (public_id),
  UNIQUE KEY uk_change_event_change_id (change_id),
  KEY idx_change_event_window (cluster, namespace, occurred_at),
  KEY idx_change_event_occurred (occurred_at)
) ENGINE = InnoDB;
