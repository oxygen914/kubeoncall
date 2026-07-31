-- Query-budget indexes for WBS-5 dashboard/list reads.  They preserve the existing access paths
-- while bounding the common active-status, non-deleted time-window scans used by list and overview.

ALTER TABLE koc_alarm_incident
  ADD KEY idx_alarm_list_status_deleted_seen (status, deleted_at, last_seen, id),
  ADD KEY idx_alarm_list_severity_deleted_seen (severity_rank, deleted_at, last_seen, id);

ALTER TABLE koc_approval_request
  ADD KEY idx_approval_status_requested (status, requested_at, id);
