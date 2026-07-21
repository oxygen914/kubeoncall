-- KubeOnCall identity, RBAC and login audit.
-- Scope: WBS-4 identity foundation. Alarm/execution/audit fact tables arrive in later migrations
-- (Expand/Contract), so this file intentionally only owns the identity domain.

-- Roles ---------------------------------------------------------------------
CREATE TABLE koc_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  built_in BOOLEAN NOT NULL DEFAULT FALSE,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_public_id (public_id),
  UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB;

-- Permissions ---------------------------------------------------------------
CREATE TABLE koc_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(128) NOT NULL,
  resource VARCHAR(64) NOT NULL,
  action VARCHAR(64) NOT NULL,
  description VARCHAR(512) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_permission_code (code),
  KEY idx_permission_resource (resource, action)
) ENGINE = InnoDB;

-- Users ---------------------------------------------------------------------
CREATE TABLE koc_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  username VARCHAR(128) NOT NULL,
  username_normalized VARCHAR(128) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  email VARCHAR(320) NULL,
  password_hash VARCHAR(255) NOT NULL,
  password_algorithm VARCHAR(32) NOT NULL DEFAULT 'BCRYPT',
  password_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  auth_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  password_changed_at DATETIME(6) NULL,
  last_login_at DATETIME(6) NULL,
  locked_until DATETIME(6) NULL,
  failed_login_count INT UNSIGNED NOT NULL DEFAULT 0,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_public_id (public_id),
  UNIQUE KEY uk_user_username_normalized (username_normalized),
  KEY idx_user_status (status, id),
  CONSTRAINT chk_user_status
    CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED', 'PASSWORD_CHANGE_REQUIRED'))
) ENGINE = InnoDB;

-- User <-> Role -------------------------------------------------------------
CREATE TABLE koc_user_role (
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  assigned_by BIGINT UNSIGNED NULL,
  assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id, role_id),
  KEY idx_user_role_role (role_id, user_id),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES koc_user (id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES koc_role (id)
) ENGINE = InnoDB;

-- Role <-> Permission -------------------------------------------------------
CREATE TABLE koc_role_permission (
  role_id BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, permission_id),
  KEY idx_role_permission_permission (permission_id, role_id),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES koc_role (id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES koc_permission (id)
) ENGINE = InnoDB;

-- API tokens ----------------------------------------------------------------
CREATE TABLE koc_api_token (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL,
  token_prefix VARCHAR(32) NOT NULL,
  token_hash BINARY(32) NOT NULL,
  scopes_json JSON NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  last_used_at DATETIME(6) NULL,
  last_used_ip VARBINARY(16) NULL,
  revoked_at DATETIME(6) NULL,
  revoked_by BIGINT UNSIGNED NULL,
  revoke_reason VARCHAR(512) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_api_token_public_id (public_id),
  UNIQUE KEY uk_api_token_hash (token_hash),
  KEY idx_api_token_prefix (token_prefix),
  KEY idx_api_token_owner (owner_user_id, revoked_at, expires_at),
  CONSTRAINT fk_api_token_owner FOREIGN KEY (owner_user_id) REFERENCES koc_user (id)
) ENGINE = InnoDB;

-- Login audit (append-only) -------------------------------------------------
CREATE TABLE koc_login_audit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(40) NOT NULL,
  user_id BIGINT UNSIGNED NULL,
  username_snapshot VARCHAR(128) NOT NULL,
  result VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NULL,
  source_ip VARBINARY(16) NULL,
  user_agent VARCHAR(512) NULL,
  request_id VARCHAR(64) NOT NULL,
  occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_login_audit_public_id (public_id),
  KEY idx_login_audit_user_time (user_id, occurred_at, id),
  KEY idx_login_audit_username_time (username_snapshot, occurred_at, id),
  KEY idx_login_audit_result_time (result, occurred_at, id),
  KEY idx_login_audit_request_id (request_id)
) ENGINE = InnoDB;

-- Built-in roles and permission seed ----------------------------------------
INSERT INTO koc_role (public_id, code, name, description, built_in) VALUES
  ('rol_viewer_01', 'VIEWER', 'Viewer', 'Read-only and safe-query access', TRUE),
  ('rol_operator_01', 'OPERATOR', 'Operator', 'On-call triage and approvals', TRUE),
  ('rol_admin_01', 'ADMIN', 'Administrator', 'Full platform administration', TRUE);

INSERT INTO koc_permission (code, resource, action, description) VALUES
  ('dashboard:read', 'dashboard', 'read', 'View the overview dashboard'),
  ('alarm:read', 'alarm', 'read', 'View alarms and timelines'),
  ('alarm:acknowledge', 'alarm', 'acknowledge', 'Acknowledge alarms'),
  ('alarm:recover', 'alarm', 'recover', 'Confirm alarm recovery'),
  ('alarm:silence', 'alarm', 'silence', 'Manage silences'),
  ('execution:read', 'execution', 'read', 'View executions and nodes'),
  ('execution:create', 'execution', 'create', 'Start executions'),
  ('execution:cancel', 'execution', 'cancel', 'Cancel executions'),
  ('execution:retry', 'execution', 'retry', 'Retry executions'),
  ('approval:read', 'approval', 'read', 'View approvals'),
  ('approval:decide', 'approval', 'decide', 'Decide approvals'),
  ('ask:execute', 'ask', 'execute', 'Run read-only ask/diagnosis'),
  ('knowledge:read', 'knowledge', 'read', 'View knowledge documents'),
  ('knowledge:write', 'knowledge', 'write', 'Import knowledge'),
  ('knowledge:delete', 'knowledge', 'delete', 'Delete and restore knowledge'),
  ('knowledge:index-manage', 'knowledge', 'index-manage', 'Manage ES index lifecycle'),
  ('memory:read', 'memory', 'read', 'View memory'),
  ('memory:write', 'memory', 'write', 'Write memory'),
  ('memory:maintain', 'memory', 'maintain', 'Run memory cleanup and consolidation'),
  ('skill:read', 'skill', 'read', 'View skills'),
  ('skill:manage', 'skill', 'manage', 'Enable, disable and reload skills'),
  ('tool:read', 'tool', 'read', 'View tools'),
  ('policy:read', 'policy', 'read', 'View alarm policies'),
  ('policy:manage', 'policy', 'manage', 'Publish and rollback policies'),
  ('maintenance:read', 'maintenance', 'read', 'View maintenance windows'),
  ('maintenance:manage', 'maintenance', 'manage', 'Manage maintenance windows'),
  ('change:read', 'change', 'read', 'View change events'),
  ('change:write', 'change', 'write', 'Ingest change events'),
  ('integration:read', 'integration', 'read', 'View integrations'),
  ('integration:manage', 'integration', 'manage', 'Manage integrations'),
  ('user:read', 'user', 'read', 'View users and roles'),
  ('user:manage', 'user', 'manage', 'Manage users and roles'),
  ('token:read-own', 'token', 'read-own', 'View own API tokens'),
  ('token:manage-own', 'token', 'manage-own', 'Manage own API tokens'),
  ('token:manage-all', 'token', 'manage-all', 'Manage all API tokens'),
  ('audit:read', 'audit', 'read', 'View audit logs'),
  ('audit:export', 'audit', 'export', 'Export audit logs'),
  ('system:read', 'system', 'read', 'View system and dependency status'),
  ('system:manage', 'system', 'manage', 'Manage system configuration');

-- Viewer: read-only access across the console.
INSERT INTO koc_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM koc_role r JOIN koc_permission p
WHERE r.code = 'VIEWER' AND p.code IN (
  'dashboard:read', 'alarm:read', 'execution:read', 'approval:read', 'ask:execute',
  'knowledge:read', 'memory:read', 'skill:read', 'tool:read', 'policy:read',
  'maintenance:read', 'change:read', 'integration:read', 'user:read', 'token:read-own',
  'audit:read', 'system:read');

-- Operator: Viewer + on-call triage and approval decisions.
INSERT INTO koc_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM koc_role r JOIN koc_permission p
WHERE r.code = 'OPERATOR' AND p.code IN (
  'dashboard:read', 'alarm:read', 'alarm:acknowledge', 'alarm:recover', 'alarm:silence',
  'execution:read', 'execution:create', 'execution:cancel', 'execution:retry',
  'approval:read', 'approval:decide', 'ask:execute', 'knowledge:read', 'memory:read',
  'skill:read', 'tool:read', 'policy:read', 'maintenance:read', 'maintenance:manage',
  'change:read', 'integration:read', 'user:read', 'token:read-own', 'token:manage-own',
  'audit:read', 'system:read');

-- Admin: full platform administration.
INSERT INTO koc_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM koc_role r JOIN koc_permission p WHERE r.code = 'ADMIN';
