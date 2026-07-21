-- KubeOnCall alarm commands, operation audit, outbox and idempotency.
-- Scope: WBS-6. Adds the ack/silence fact tables the command service writes, plus the cross-cutting
-- operation-audit, outbox and idempotency infrastructure that every high-risk write must use so the
-- business update, audit and outbox commit atomically in one transaction.

-- Alarm acknowledgement -----------------------------------------------------
CREATE TABLE koc_alarm_acknowledgement (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  incident_id BIGINT UNSIGNED NOT NULL,
  acknowledged_by BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  acknowledged_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  request_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_ack_public_id (public_id),
  KEY idx_alarm_ack_incident_time (incident_id, acknowledged_at, id),
  CONSTRAINT fk_alarm_ack_incident FOREIGN KEY (incident_id) REFERENCES koc_alarm_incident (id),
  CONSTRAINT fk_alarm_ack_user FOREIGN KEY (acknowledged_by) REFERENCES koc_user (id)
) ENGINE = InnoDB;

-- Alarm silence approval ----------------------------------------------------
CREATE TABLE koc_alarm_silence (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  incident_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(32) NOT NULL,
  approved_by BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  approved_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  external_silence_id VARCHAR(255) NULL,
  request_id VARCHAR(64) NOT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_silence_public_id (public_id),
  KEY idx_alarm_silence_incident_status (incident_id, status, expires_at),
  CONSTRAINT fk_alarm_silence_incident FOREIGN KEY (incident_id) REFERENCES koc_alarm_incident (id),
  CONSTRAINT fk_alarm_silence_user FOREIGN KEY (approved_by) REFERENCES koc_user (id)
) ENGINE = InnoDB;

-- Operation audit (append-only, business-tx integrated) ---------------------
CREATE TABLE koc_operation_audit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  actor_type VARCHAR(32) NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  actor_display_name VARCHAR(255) NULL,
  action VARCHAR(128) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_public_id VARCHAR(40) NULL,
  result VARCHAR(32) NOT NULL,
  reason VARCHAR(1000) NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  request_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NULL,
  source_ip VARBINARY(16) NULL,
  user_agent VARCHAR(512) NULL,
  occurred_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_operation_audit_public_id (public_id),
  KEY idx_audit_time (occurred_at, id),
  KEY idx_audit_resource (resource_type, resource_public_id, occurred_at, id),
  KEY idx_audit_actor (actor_id, occurred_at, id),
  KEY idx_audit_action (action, occurred_at, id),
  KEY idx_audit_request_id (request_id)
) ENGINE = InnoDB;

-- Outbox (reliable post-commit side effects) ---------------------------------
CREATE TABLE koc_outbox_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(40) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_public_id VARCHAR(40) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  schema_version INT UNSIGNED NOT NULL DEFAULT 1,
  payload_json JSON NOT NULL,
  request_id VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts INT UNSIGNED NOT NULL DEFAULT 10,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  owner_token VARCHAR(128) NULL,
  lease_until DATETIME(6) NULL,
  published_at DATETIME(6) NULL,
  last_error_code VARCHAR(128) NULL,
  last_error_summary VARCHAR(2000) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event_id (event_id),
  KEY idx_outbox_claim (status, next_attempt_at, lease_until, id),
  KEY idx_outbox_aggregate (aggregate_type, aggregate_public_id, id)
) ENGINE = InnoDB;

-- Idempotency records --------------------------------------------------------
CREATE TABLE koc_idempotency_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  principal_type VARCHAR(32) NOT NULL,
  principal_public_id VARCHAR(64) NOT NULL,
  route_key VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  http_status SMALLINT UNSIGNED NULL,
  response_json JSON NULL,
  resource_type VARCHAR(64) NULL,
  resource_public_id VARCHAR(40) NULL,
  task_public_id VARCHAR(40) NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotency_scope (principal_type, principal_public_id, route_key, idempotency_key),
  KEY idx_idempotency_expiry (expires_at, id)
) ENGINE = InnoDB;
