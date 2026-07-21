-- KubeOnCall durable workflow execution, approval and async-task facts.
-- Scope: WBS-7. MySQL owns queryable summaries and worker ownership metadata while
-- graph snapshots remain referenced from Redis through graph_state_key.

-- Workflow execution --------------------------------------------------------
CREATE TABLE koc_workflow_execution (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  type VARCHAR(64) NOT NULL,
  trigger_type VARCHAR(64) NOT NULL,
  trigger_public_id VARCHAR(40) NULL,
  dedupe_key VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  risk_level VARCHAR(16) NOT NULL,
  summary VARCHAR(2000) NULL,
  result_summary VARCHAR(4000) NULL,
  error_code VARCHAR(128) NULL,
  error_summary VARCHAR(2000) NULL,
  actor_type VARCHAR(32) NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  session_id VARCHAR(128) NULL,
  request_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NULL,
  graph_state_key VARCHAR(512) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_execution_public_id (public_id),
  UNIQUE KEY uk_workflow_execution_dedupe (type, dedupe_key),
  KEY idx_workflow_execution_status_time (status, created_at, id),
  KEY idx_workflow_execution_trigger (trigger_type, trigger_public_id, created_at, id),
  KEY idx_workflow_execution_actor (actor_id, created_at, id),
  KEY idx_workflow_execution_request (request_id),
  CONSTRAINT fk_workflow_execution_actor FOREIGN KEY (actor_id) REFERENCES koc_user (id),
  CONSTRAINT chk_workflow_execution_status CHECK (
    status IN (
      'PENDING', 'RUNNING', 'WAITING_APPROVAL', 'APPROVED', 'REJECTED',
      'SUCCEEDED', 'FAILED', 'CANCELLED'
    )
  ),
  CONSTRAINT chk_workflow_execution_risk CHECK (
    risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
  )
) ENGINE = InnoDB;

-- Workflow node execution ---------------------------------------------------
CREATE TABLE koc_workflow_node_execution (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  execution_id BIGINT UNSIGNED NOT NULL,
  node_name VARCHAR(128) NOT NULL,
  node_type VARCHAR(128) NOT NULL,
  attempt INT UNSIGNED NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  input_summary VARCHAR(4000) NULL,
  output_summary VARCHAR(4000) NULL,
  error_code VARCHAR(128) NULL,
  error_summary VARCHAR(2000) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  duration_ms BIGINT UNSIGNED NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_node_public_id (public_id),
  UNIQUE KEY uk_workflow_node_attempt (execution_id, node_name, attempt),
  KEY idx_workflow_node_execution_status (execution_id, status, id),
  KEY idx_workflow_node_status_time (status, created_at, id),
  CONSTRAINT fk_workflow_node_execution
    FOREIGN KEY (execution_id) REFERENCES koc_workflow_execution (id),
  CONSTRAINT chk_workflow_node_attempt CHECK (attempt > 0),
  CONSTRAINT chk_workflow_node_status CHECK (
    status IN (
      'PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SKIPPED',
      'SUCCEEDED', 'FAILED', 'CANCELLED'
    )
  )
) ENGINE = InnoDB;

-- Approval request ----------------------------------------------------------
CREATE TABLE koc_approval_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  execution_id BIGINT UNSIGNED NOT NULL,
  action_type VARCHAR(128) NOT NULL,
  dedupe_key VARCHAR(255) NOT NULL,
  risk_level VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  context_json JSON NULL,
  requested_by BIGINT UNSIGNED NOT NULL,
  requested_at DATETIME(6) NOT NULL,
  decided_by BIGINT UNSIGNED NULL,
  decided_at DATETIME(6) NULL,
  decision VARCHAR(32) NULL,
  comment VARCHAR(2000) NULL,
  expires_at DATETIME(6) NOT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_approval_request_public_id (public_id),
  UNIQUE KEY uk_approval_request_dedupe (execution_id, action_type, dedupe_key),
  KEY idx_approval_status_expiry (status, expires_at, id),
  KEY idx_approval_execution_status (execution_id, status, requested_at, id),
  KEY idx_approval_requester_time (requested_by, requested_at, id),
  KEY idx_approval_decider_time (decided_by, decided_at, id),
  CONSTRAINT fk_approval_execution
    FOREIGN KEY (execution_id) REFERENCES koc_workflow_execution (id),
  CONSTRAINT fk_approval_requested_by FOREIGN KEY (requested_by) REFERENCES koc_user (id),
  CONSTRAINT fk_approval_decided_by FOREIGN KEY (decided_by) REFERENCES koc_user (id),
  CONSTRAINT chk_approval_risk CHECK (
    risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
  ),
  CONSTRAINT chk_approval_status CHECK (
    status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')
  ),
  CONSTRAINT chk_approval_decision CHECK (
    decision IS NULL OR decision IN ('APPROVED', 'REJECTED')
  )
) ENGINE = InnoDB;

-- Generic async task --------------------------------------------------------
CREATE TABLE koc_async_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  task_type VARCHAR(128) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_public_id VARCHAR(40) NULL,
  dedupe_key VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  stage VARCHAR(128) NULL,
  progress TINYINT UNSIGNED NOT NULL DEFAULT 0,
  request_json JSON NULL,
  result_json JSON NULL,
  error_code VARCHAR(128) NULL,
  error_summary VARCHAR(2000) NULL,
  owner_token VARCHAR(128) NULL,
  lease_until DATETIME(6) NULL,
  fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts INT UNSIGNED NOT NULL DEFAULT 5,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  request_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_async_task_public_id (public_id),
  UNIQUE KEY uk_async_task_dedupe (task_type, dedupe_key),
  KEY idx_async_task_claim (status, next_attempt_at, lease_until, id),
  KEY idx_async_task_resource (resource_type, resource_public_id, created_at, id),
  KEY idx_async_task_owner (owner_token, lease_until, id),
  KEY idx_async_task_request (request_id),
  CONSTRAINT chk_async_task_status CHECK (
    status IN (
      'PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'FAILED',
      'DEAD_LETTER', 'CANCELLED'
    )
  ),
  CONSTRAINT chk_async_task_progress CHECK (progress BETWEEN 0 AND 100),
  CONSTRAINT chk_async_task_attempt CHECK (attempt <= max_attempts AND max_attempts > 0)
) ENGINE = InnoDB;

-- The alarm table deliberately carried this nullable column since V2 so the FK
-- can be added after the execution table exists without a destructive rewrite.
ALTER TABLE koc_alarm_incident
  ADD KEY idx_alarm_latest_execution (latest_execution_id),
  ADD CONSTRAINT fk_alarm_latest_execution
    FOREIGN KEY (latest_execution_id) REFERENCES koc_workflow_execution (id)
    ON DELETE SET NULL;
