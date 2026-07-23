-- A durable, transaction-scoped source-key claim prevents a crash between a command fact write and
-- its best-effort migration ledger record from producing a duplicate fact on replay.
CREATE TABLE koc_migration_source_claim (
  domain VARCHAR(64) NOT NULL,
  source_key VARCHAR(512) NOT NULL,
  target_public_id VARCHAR(40) NULL,
  claimed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (domain, source_key),
  KEY idx_migration_source_claim_target (target_public_id)
) ENGINE = InnoDB;
