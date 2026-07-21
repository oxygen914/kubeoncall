-- Manual recovery confirmations are immutable command facts. The incident keeps the current
-- lifecycle snapshot while this table preserves the actor, health assertion and operator note.

CREATE TABLE koc_alarm_recovery_confirmation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  incident_id BIGINT UNSIGNED NOT NULL,
  confirmed_by BIGINT UNSIGNED NOT NULL,
  health_check_passed BOOLEAN NOT NULL,
  note VARCHAR(1000) NOT NULL,
  confirmed_at DATETIME(6) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_recovery_public_id (public_id),
  KEY idx_alarm_recovery_incident_time (incident_id, confirmed_at, id),
  CONSTRAINT fk_alarm_recovery_incident
    FOREIGN KEY (incident_id) REFERENCES koc_alarm_incident (id),
  CONSTRAINT fk_alarm_recovery_user
    FOREIGN KEY (confirmed_by) REFERENCES koc_user (id)
) ENGINE = InnoDB;
