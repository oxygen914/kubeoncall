-- Evidence v2 and structured AI conclusions for durable Ask executions.

CREATE TABLE koc_evidence_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  execution_id BIGINT UNSIGNED NOT NULL,
  evidence_type VARCHAR(64) NOT NULL,
  source VARCHAR(128) NOT NULL,
  cluster_name VARCHAR(128) NULL,
  namespace_name VARCHAR(253) NULL,
  resource_kind VARCHAR(128) NULL,
  resource_name VARCHAR(253) NULL,
  resource_uid VARCHAR(128) NULL,
  observed_at DATETIME(6) NOT NULL,
  window_start DATETIME(6) NULL,
  window_end DATETIME(6) NULL,
  summary VARCHAR(2000) NOT NULL,
  snippet MEDIUMTEXT NULL,
  locator_json JSON NULL,
  freshness_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0,
  redacted BOOLEAN NOT NULL DEFAULT TRUE,
  truncated BOOLEAN NOT NULL DEFAULT FALSE,
  content_hash VARCHAR(80) NOT NULL,
  collection_status VARCHAR(32) NOT NULL,
  error_type VARCHAR(128) NULL,
  artifact_reference VARCHAR(1024) NULL,
  metadata_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_evidence_public_id (public_id),
  UNIQUE KEY uk_evidence_execution_content (execution_id, evidence_type, source, content_hash),
  KEY idx_evidence_execution_time (execution_id, observed_at, id),
  KEY idx_evidence_resource_uid (resource_uid, observed_at, id),
  KEY idx_evidence_source_status (source, collection_status, created_at, id),
  CONSTRAINT fk_evidence_execution
    FOREIGN KEY (execution_id) REFERENCES koc_workflow_execution (id),
  CONSTRAINT chk_evidence_status CHECK (
    collection_status IN ('SUCCEEDED', 'EMPTY', 'UNAVAILABLE', 'FORBIDDEN', 'FAILED')
  )
) ENGINE = InnoDB;

CREATE TABLE koc_ai_conclusion (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  execution_id BIGINT UNSIGNED NOT NULL,
  claim_text VARCHAR(4000) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  support_status VARCHAR(32) NOT NULL,
  evidence_refs_json JSON NOT NULL,
  sop_refs_json JSON NOT NULL,
  confidence_score DECIMAL(5,4) NOT NULL,
  confidence_label VARCHAR(16) NOT NULL,
  confidence_basis_json JSON NOT NULL,
  planner_json JSON NOT NULL,
  recommended_action_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_conclusion_public_id (public_id),
  KEY idx_conclusion_execution (execution_id, created_at, id),
  KEY idx_conclusion_status_confidence (support_status, confidence_score, created_at, id),
  CONSTRAINT fk_conclusion_execution
    FOREIGN KEY (execution_id) REFERENCES koc_workflow_execution (id),
  CONSTRAINT chk_conclusion_support_status CHECK (
    support_status IN ('SUPPORTED', 'PARTIALLY_SUPPORTED', 'UNSUPPORTED', 'CONFLICTED')
  ),
  CONSTRAINT chk_conclusion_confidence CHECK (
    confidence_score >= 0 AND confidence_score <= 1
  )
) ENGINE = InnoDB;
