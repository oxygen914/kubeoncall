-- Durable, provider-neutral notification delivery records.
-- One row represents one physical destination so retries never replay destinations that already
-- succeeded in the same logical route.

CREATE TABLE koc_notification_delivery (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  delivery_key VARCHAR(255) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  routing_key VARCHAR(128) NOT NULL,
  provider_key VARCHAR(64) NOT NULL,
  destination_id VARCHAR(128) NOT NULL,
  target_alias VARCHAR(128) NOT NULL,
  operation VARCHAR(32) NOT NULL DEFAULT 'SEND',
  priority VARCHAR(32) NOT NULL,
  title VARCHAR(512) NOT NULL,
  summary VARCHAR(2000) NOT NULL,
  message_json JSON NOT NULL,
  destination_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  replay_count INT UNSIGNED NOT NULL DEFAULT 0,
  retryable BOOLEAN NOT NULL DEFAULT FALSE,
  external_message_id VARCHAR(255) NULL,
  provider_code VARCHAR(128) NULL,
  last_error_code VARCHAR(128) NULL,
  last_error_summary VARCHAR(2000) NULL,
  request_id VARCHAR(64) NULL,
  delivered_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_delivery_public_id (public_id),
  UNIQUE KEY uk_notification_delivery_key (delivery_key),
  KEY idx_notification_delivery_status (status, updated_at, id),
  KEY idx_notification_delivery_event (event_id, id),
  KEY idx_notification_delivery_provider (provider_key, created_at, id),
  CONSTRAINT chk_notification_delivery_operation
    CHECK (operation IN ('SEND', 'UPDATE', 'RECALL')),
  CONSTRAINT chk_notification_delivery_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'RETRYING', 'DELIVERED', 'FAILED', 'DEAD_LETTER'))
) ENGINE = InnoDB;
