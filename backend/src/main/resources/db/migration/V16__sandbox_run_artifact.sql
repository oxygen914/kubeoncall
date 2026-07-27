-- KubeOnCall sandbox run and artifact facts (SBX-04).
-- Scope: persist sandbox run lifecycle, ownership/fencing metadata and artifact references. MySQL
-- holds only summaries, references and checksums — script bodies, full logs and large results stay
-- in MinIO (§6.3). Forward-compatible additive only: no existing table is touched and no down
-- migration ships. If the sandbox is rolled back at the code level these tables simply remain
-- unreferenced; a TTL janitor cleans orphaned MinIO objects independently.

-- Sandbox run ----------------------------------------------------------------
CREATE TABLE koc_sandbox_run (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  execution_public_id VARCHAR(40) NULL,
  alarm_public_id VARCHAR(40) NULL,
  mode VARCHAR(32) NOT NULL,
  tool_id VARCHAR(128) NOT NULL,
  tool_version VARCHAR(64) NOT NULL,
  runtime_image_digest VARCHAR(512) NOT NULL,
  run_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  cleanup_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
  stage VARCHAR(128) NULL,
  progress INT NOT NULL DEFAULT 0,
  risk_level VARCHAR(16) NOT NULL,
  requested_by VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_json JSON NULL,
  result_json JSON NULL,
  error_code VARCHAR(128) NULL,
  error_summary VARCHAR(2000) NULL,
  controller_run_id VARCHAR(128) NULL,
  owner_token VARCHAR(128) NULL,
  lease_until DATETIME(6) NULL,
  fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts INT UNSIGNED NOT NULL DEFAULT 1,
  expires_at DATETIME(6) NULL,
  request_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sandbox_run_public_id (public_id),
  -- (mode, idempotency_key) dedupes repeated create requests so a retried dispatch never produces
  -- a second run (§6.4, SBX-04).
  UNIQUE KEY uk_sandbox_run_mode_idempotency (mode, idempotency_key),
  KEY idx_sandbox_run_status_time (run_status, created_at, id),
  KEY idx_sandbox_run_alarm (alarm_public_id, created_at, id),
  KEY idx_sandbox_run_execution (execution_public_id, created_at, id),
  KEY idx_sandbox_run_controller (controller_run_id),
  KEY idx_sandbox_run_reconcile (run_status, lease_until, id),
  KEY idx_sandbox_run_request (request_id),
  CONSTRAINT chk_sandbox_run_mode CHECK (
    mode IN ('FIXED_DIAGNOSTIC', 'GENERATED_CODE', 'MANIFEST_VALIDATION', 'REMEDIATION_SIMULATION')
  ),
  CONSTRAINT chk_sandbox_run_status CHECK (
    run_status IN ('PENDING', 'DISPATCHING', 'RUNNING', 'COLLECTING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
  ),
  CONSTRAINT chk_sandbox_run_cleanup CHECK (
    cleanup_status IN ('NOT_REQUIRED', 'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')
  ),
  CONSTRAINT chk_sandbox_run_risk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH'))
) ENGINE = InnoDB;

-- Sandbox artifact ----------------------------------------------------------
CREATE TABLE koc_sandbox_artifact (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  sandbox_run_id BIGINT UNSIGNED NOT NULL,
  artifact_type VARCHAR(16) NOT NULL,
  bucket VARCHAR(128) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  classification VARCHAR(16) NOT NULL DEFAULT 'UNTRUSTED',
  retention_until DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sandbox_artifact_public_id (public_id),
  UNIQUE KEY uk_sandbox_artifact_object (bucket, object_key),
  KEY idx_sandbox_artifact_run (sandbox_run_id, artifact_type, id),
  KEY idx_sandbox_artifact_retention (retention_until, id),
  CONSTRAINT fk_sandbox_artifact_run FOREIGN KEY (sandbox_run_id) REFERENCES koc_sandbox_run (id),
  CONSTRAINT chk_sandbox_artifact_type CHECK (artifact_type IN ('INPUT', 'OUTPUT', 'LOG', 'REPORT')),
  CONSTRAINT chk_sandbox_artifact_class CHECK (classification IN ('PUBLIC', 'INTERNAL', 'UNTRUSTED'))
) ENGINE = InnoDB;
