-- MySQL fencing for Redis -> MySQL migration runners.
--
-- Redis ownership prevents concurrent scans, but a paused owner can resume after its lease has
-- expired. Each successor advances this generation. Every MySQL fact write performed by a
-- backfill runner locks and verifies this row in the same transaction as the fact write, so an
-- owner from an earlier generation cannot commit after a successor has fenced it out.

CREATE TABLE koc_migration_write_fence (
  domain VARCHAR(64) NOT NULL,
  owner_token VARCHAR(128) NOT NULL,
  generation BIGINT UNSIGNED NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (domain)
) ENGINE = InnoDB;
