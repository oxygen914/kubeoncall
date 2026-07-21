-- KubeOnCall durable knowledge, memory and Skill governance facts.
-- Scope: WBS-9. Large content/error reports remain in MinIO and indexed content
-- remains in Elasticsearch; MySQL stores lifecycle, ownership and immutable refs.

CREATE TABLE koc_knowledge_document (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  external_document_id VARCHAR(255) NULL,
  title VARCHAR(1000) NOT NULL,
  source_type VARCHAR(64) NOT NULL,
  source_uri VARCHAR(2048) NULL,
  dataset_version VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  metadata_json JSON NULL,
  current_version_id BIGINT UNSIGNED NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_by BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  deleted_by BIGINT UNSIGNED NULL,
  delete_reason VARCHAR(1000) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_document_public_id (public_id),
  UNIQUE KEY uk_knowledge_external_id (source_type, external_document_id),
  KEY idx_knowledge_document_status_time (status, updated_at, id),
  KEY idx_knowledge_document_dataset (dataset_version, status, id),
  KEY idx_knowledge_document_deleted (deleted_at, id),
  CONSTRAINT fk_knowledge_document_creator FOREIGN KEY (created_by) REFERENCES koc_user (id),
  CONSTRAINT fk_knowledge_document_deleter FOREIGN KEY (deleted_by) REFERENCES koc_user (id),
  CONSTRAINT chk_knowledge_document_status CHECK (status IN ('ACTIVE', 'DELETED'))
) ENGINE = InnoDB;

CREATE TABLE koc_knowledge_import (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  task_id BIGINT UNSIGNED NOT NULL,
  import_type VARCHAR(32) NOT NULL,
  duplicate_policy VARCHAR(32) NOT NULL DEFAULT 'SKIP',
  dry_run BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  source_bucket VARCHAR(128) NOT NULL,
  source_object_key VARCHAR(1024) NOT NULL,
  source_checksum BINARY(32) NOT NULL,
  source_size_bytes BIGINT UNSIGNED NOT NULL,
  dataset_version VARCHAR(128) NULL,
  total_count BIGINT UNSIGNED NULL,
  processed_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  succeeded_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  failed_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  skipped_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  error_report_bucket VARCHAR(128) NULL,
  error_report_object_key VARCHAR(1024) NULL,
  error_code VARCHAR(128) NULL,
  error_summary VARCHAR(2000) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_import_public_id (public_id),
  UNIQUE KEY uk_knowledge_import_task (task_id),
  UNIQUE KEY uk_knowledge_import_source (source_checksum, import_type, dry_run),
  KEY idx_knowledge_import_status_time (status, updated_at, id),
  KEY idx_knowledge_import_dataset (dataset_version, created_at, id),
  CONSTRAINT fk_knowledge_import_task FOREIGN KEY (task_id) REFERENCES koc_async_task (id),
  CONSTRAINT chk_knowledge_import_status CHECK (
    status IN ('PENDING', 'RUNNING', 'PARTIAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')
  ),
  CONSTRAINT chk_knowledge_import_policy CHECK (
    duplicate_policy IN ('SKIP', 'REPLACE', 'FAIL')
  ),
  CONSTRAINT chk_knowledge_import_counts CHECK (
    processed_count = succeeded_count + failed_count + skipped_count
    AND (total_count IS NULL OR processed_count <= total_count)
  )
) ENGINE = InnoDB;

CREATE TABLE koc_knowledge_document_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  import_id BIGINT UNSIGNED NULL,
  version_no INT UNSIGNED NOT NULL,
  checksum BINARY(32) NOT NULL,
  content_type VARCHAR(255) NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  object_bucket VARCHAR(128) NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  es_index VARCHAR(255) NULL,
  es_document_id VARCHAR(255) NULL,
  index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  embedding_model VARCHAR(255) NULL,
  embedding_version VARCHAR(128) NULL,
  embedding_dimensions INT UNSIGNED NULL,
  augmentation_model VARCHAR(255) NULL,
  augmentation_version VARCHAR(128) NULL,
  metadata_json JSON NULL,
  indexed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_version_public_id (public_id),
  UNIQUE KEY uk_knowledge_version_no (document_id, version_no),
  UNIQUE KEY uk_knowledge_version_checksum (document_id, checksum),
  KEY idx_knowledge_version_index_status (index_status, id),
  KEY idx_knowledge_version_import (import_id, id),
  CONSTRAINT fk_knowledge_version_document
    FOREIGN KEY (document_id) REFERENCES koc_knowledge_document (id),
  CONSTRAINT fk_knowledge_version_import
    FOREIGN KEY (import_id) REFERENCES koc_knowledge_import (id),
  CONSTRAINT chk_knowledge_version_number CHECK (version_no > 0),
  CONSTRAINT chk_knowledge_version_index_status CHECK (
    index_status IN ('PENDING', 'INDEXING', 'INDEXED', 'FAILED')
  )
) ENGINE = InnoDB;

ALTER TABLE koc_knowledge_document
  ADD CONSTRAINT fk_knowledge_document_current_version
  FOREIGN KEY (current_version_id) REFERENCES koc_knowledge_document_version (id);

CREATE TABLE koc_memory_entry (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  memory_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  source_session_id VARCHAR(128) NULL,
  source_execution_public_id VARCHAR(40) NULL,
  source_alarm_public_id VARCHAR(40) NULL,
  evidence_json JSON NOT NULL,
  quality_score DECIMAL(6, 5) NULL,
  content_checksum BINARY(32) NOT NULL,
  es_index VARCHAR(255) NULL,
  es_document_id VARCHAR(255) NULL,
  extracted_at DATETIME(6) NULL,
  expires_at DATETIME(6) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  delete_reason VARCHAR(1000) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_memory_entry_public_id (public_id),
  UNIQUE KEY uk_memory_entry_dedupe (memory_type, content_checksum),
  KEY idx_memory_entry_status_time (status, updated_at, id),
  KEY idx_memory_entry_source_execution (source_execution_public_id, id),
  KEY idx_memory_entry_source_alarm (source_alarm_public_id, id),
  KEY idx_memory_entry_expiry (expires_at, id),
  CONSTRAINT chk_memory_entry_status CHECK (status IN ('ACTIVE', 'DELETED')),
  CONSTRAINT chk_memory_entry_quality CHECK (
    quality_score IS NULL OR (quality_score >= 0 AND quality_score <= 1)
  )
) ENGINE = InnoDB;

CREATE TABLE koc_memory_extraction_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  task_id BIGINT UNSIGNED NOT NULL,
  source_type VARCHAR(64) NOT NULL,
  source_public_id VARCHAR(128) NOT NULL,
  dedupe_key VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  extractor_model VARCHAR(255) NULL,
  extractor_version VARCHAR(128) NULL,
  evidence_count INT UNSIGNED NOT NULL DEFAULT 0,
  memory_count INT UNSIGNED NOT NULL DEFAULT 0,
  quality_summary_json JSON NULL,
  error_code VARCHAR(128) NULL,
  error_summary VARCHAR(2000) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_memory_extraction_public_id (public_id),
  UNIQUE KEY uk_memory_extraction_task (task_id),
  UNIQUE KEY uk_memory_extraction_dedupe (source_type, source_public_id, dedupe_key),
  KEY idx_memory_extraction_status_time (status, updated_at, id),
  KEY idx_memory_extraction_source (source_type, source_public_id, id),
  CONSTRAINT fk_memory_extraction_task FOREIGN KEY (task_id) REFERENCES koc_async_task (id),
  CONSTRAINT chk_memory_extraction_status CHECK (
    status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
  )
) ENGINE = InnoDB;

CREATE TABLE koc_skill_state (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  skill_id VARCHAR(255) NOT NULL,
  skill_version VARCHAR(128) NULL,
  checksum BINARY(32) NOT NULL,
  source_location VARCHAR(1024) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  load_status VARCHAR(32) NOT NULL,
  error_summary VARCHAR(2000) NULL,
  metadata_json JSON NULL,
  last_loaded_at DATETIME(6) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  updated_by BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_skill_state_public_id (public_id),
  UNIQUE KEY uk_skill_state_skill_id (skill_id),
  KEY idx_skill_state_enabled_status (enabled, load_status, id),
  KEY idx_skill_state_checksum (checksum),
  CONSTRAINT fk_skill_state_updater FOREIGN KEY (updated_by) REFERENCES koc_user (id),
  CONSTRAINT chk_skill_state_load_status CHECK (
    load_status IN ('DISCOVERED', 'LOADING', 'LOADED', 'FAILED', 'DISABLED')
  )
) ENGINE = InnoDB;
