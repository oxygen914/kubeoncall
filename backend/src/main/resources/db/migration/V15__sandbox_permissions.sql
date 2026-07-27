-- KubeOnCall sandbox RBAC seed (SBX-02).
-- Scope: register the four sandbox permission codes and grant them to the built-in roles, mirroring
-- the §6.5 policy — Viewer reads, Operator reads/executes/cancels, Admin manages everything.
-- Forward-compatible additive only: no existing row is touched and no down migration ships. If the
-- feature is rolled back at the code level these permissions simply remain unreferenced.

INSERT INTO koc_permission (code, resource, action, description) VALUES
  ('sandbox:read',    'sandbox', 'read',    'View sandbox runs and artifacts'),
  ('sandbox:execute', 'sandbox', 'execute', 'Create sandbox runs'),
  ('sandbox:cancel',  'sandbox', 'cancel',  'Cancel sandbox runs'),
  ('sandbox:manage',  'sandbox', 'manage',  'Manage sandbox runs and configuration');

-- Viewer: read-only visibility of sandbox runs alongside its other read grants.
INSERT INTO koc_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM koc_role r JOIN koc_permission p
WHERE r.code = 'VIEWER' AND p.code = 'sandbox:read';

-- Operator: read, execute and cancel sandbox runs for on-call triage, but not manage.
INSERT INTO koc_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM koc_role r JOIN koc_permission p
WHERE r.code = 'OPERATOR' AND p.code IN ('sandbox:read', 'sandbox:execute', 'sandbox:cancel');

-- Admin: every sandbox permission, consistent with the V1 full-grant pattern.
INSERT INTO koc_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM koc_role r JOIN koc_permission p
WHERE r.code = 'ADMIN' AND p.code IN ('sandbox:read', 'sandbox:execute', 'sandbox:cancel', 'sandbox:manage');
