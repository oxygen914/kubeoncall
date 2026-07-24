-- KubeOnCall API token optimistic lock (WBS-4 GAP-04-01).
-- Scope: add a version column to koc_api_token so revoke can use compare-and-set, matching the
-- If-Match convention used by users/alarms. Expand/Contract: additive columns, no existing path
-- is touched (the table is empty until the management endpoint creates tokens).

ALTER TABLE koc_api_token
  ADD COLUMN version BIGINT UNSIGNED NOT NULL DEFAULT 1 AFTER created_at,
  ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6) AFTER version;
