-- KubeOnCall alarm read model: incident, event and status history.
-- Scope: WBS-5 alarm read model. These tables are the MySQL source of truth for the
-- /api/v1/alarms list/detail/timeline and the Dashboard overview. They are populated by the
-- shadow-write projection that mirrors ActiveAlarmStore (Redis); the legacy Redis path stays the
-- authoritative write path until the data-switch iteration (WBS-11), so failures here must never
-- block alarm processing.

CREATE TABLE koc_alarm_incident (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  fingerprint VARCHAR(128) NOT NULL,
  cycle_no INT UNSIGNED NOT NULL DEFAULT 1,
  alert_name VARCHAR(255) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  severity_rank TINYINT UNSIGNED NOT NULL,
  status VARCHAR(32) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_name VARCHAR(255) NOT NULL,
  cluster_name VARCHAR(255) NULL,
  namespace_name VARCHAR(255) NULL,
  service_name VARCHAR(255) NULL,
  metric_name VARCHAR(255) NULL,
  current_value DECIMAL(30, 10) NULL,
  threshold_value DECIMAL(30, 10) NULL,
  unit VARCHAR(64) NULL,
  labels_json JSON NULL,
  annotations_json JSON NULL,
  first_seen DATETIME(6) NOT NULL,
  last_seen DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  occurrence_count BIGINT UNSIGNED NOT NULL DEFAULT 1,
  acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
  acknowledged_by BIGINT UNSIGNED NULL,
  acknowledged_at DATETIME(6) NULL,
  assignee_id BIGINT UNSIGNED NULL,
  latest_execution_id BIGINT UNSIGNED NULL,
  policy_public_id VARCHAR(40) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_public_id (public_id),
  UNIQUE KEY uk_alarm_fingerprint_cycle (fingerprint, cycle_no),
  KEY idx_alarm_status_last_seen (status, last_seen, id),
  KEY idx_alarm_severity_status_last_seen (severity_rank, status, last_seen, id),
  KEY idx_alarm_cluster_status_last_seen (cluster_name, status, last_seen, id),
  KEY idx_alarm_service_status_last_seen (service_name, status, last_seen, id),
  KEY idx_alarm_assignee_status (assignee_id, status, last_seen, id),
  CONSTRAINT chk_alarm_severity CHECK (severity IN ('P0', 'P1', 'P2', 'P3', 'INFO')),
  CONSTRAINT chk_alarm_status CHECK (
    status IN ('FIRING', 'ACKNOWLEDGED', 'RECOVERY_PENDING', 'RESOLVED', 'SUPPRESSED')
  )
) ENGINE = InnoDB;

CREATE TABLE koc_alarm_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  incident_id BIGINT UNSIGNED NOT NULL,
  source VARCHAR(64) NOT NULL,
  delivery_id VARCHAR(255) NULL,
  event_status VARCHAR(32) NOT NULL,
  starts_at DATETIME(6) NULL,
  ends_at DATETIME(6) NULL,
  received_at DATETIME(6) NOT NULL,
  payload_checksum BINARY(32) NOT NULL,
  labels_json JSON NULL,
  annotations_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_event_public_id (public_id),
  UNIQUE KEY uk_alarm_event_delivery (source, delivery_id),
  KEY idx_alarm_event_incident_time (incident_id, received_at, id),
  KEY idx_alarm_event_received (received_at, id),
  CONSTRAINT fk_alarm_event_incident FOREIGN KEY (incident_id) REFERENCES koc_alarm_incident (id),
  CONSTRAINT chk_alarm_event_status CHECK (event_status IN ('FIRING', 'RESOLVED', 'SUPPRESSED'))
) ENGINE = InnoDB;

CREATE TABLE koc_alarm_status_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  incident_id BIGINT UNSIGNED NOT NULL,
  from_status VARCHAR(32) NULL,
  to_status VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NULL,
  reason VARCHAR(1000) NULL,
  actor_type VARCHAR(32) NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  request_id VARCHAR(64) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_history_public_id (public_id),
  KEY idx_alarm_history_incident_time (incident_id, occurred_at, id),
  KEY idx_alarm_history_request_id (request_id),
  CONSTRAINT fk_alarm_history_incident FOREIGN KEY (incident_id) REFERENCES koc_alarm_incident (id)
) ENGINE = InnoDB;
