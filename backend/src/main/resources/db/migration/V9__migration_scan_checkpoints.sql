-- Durable Redis SCAN cursors for WBS-11 backfill continuation.
-- Redis cursors are opaque protocol positions, not sortable keys. Persisting the cursor after a
-- fully processed page lets a later invocation continue from that page boundary without scanning
-- the completed prefix again.

CREATE TABLE koc_migration_scan_checkpoint (
  domain VARCHAR(64) NOT NULL,
  redis_cursor VARCHAR(64) NOT NULL DEFAULT '0',
  completed BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (domain)
) ENGINE = InnoDB;
