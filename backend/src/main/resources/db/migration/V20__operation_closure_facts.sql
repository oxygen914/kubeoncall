-- Durable operation closure and human-escalation facts.
-- The resumable graph state remains in Redis; these rows are the MySQL audit/read projection used
-- to prove which operation was prepared, verified, rolled back or escalated.

CREATE TABLE koc_operation_closure_fact (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  execution_public_id VARCHAR(40) NOT NULL,
  task_public_id VARCHAR(64) NULL,
  executor_kind VARCHAR(64) NOT NULL,
  action_name VARCHAR(128) NOT NULL,
  target_name VARCHAR(512) NULL,
  phase VARCHAR(32) NOT NULL,
  details_json JSON NOT NULL,
  error_summary VARCHAR(2000) NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_operation_closure_public_id (public_id),
  UNIQUE KEY uk_operation_closure_operation_id (operation_id),
  KEY idx_operation_closure_execution (execution_public_id, updated_at, id),
  KEY idx_operation_closure_phase (phase, updated_at, id)
) ENGINE = InnoDB;

CREATE TABLE koc_operation_escalation_fact (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  execution_public_id VARCHAR(40) NOT NULL,
  status VARCHAR(32) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  summary VARCHAR(2000) NOT NULL,
  details_json JSON NOT NULL,
  last_error_summary VARCHAR(2000) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_operation_escalation_public_id (public_id),
  UNIQUE KEY uk_operation_escalation_operation_id (operation_id),
  KEY idx_operation_escalation_execution (execution_public_id, updated_at, id),
  KEY idx_operation_escalation_status (status, updated_at, id)
) ENGINE = InnoDB;
