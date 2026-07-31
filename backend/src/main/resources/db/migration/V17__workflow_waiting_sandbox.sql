-- A Sandbox route is an asynchronous evidence wait, not a human approval wait.
ALTER TABLE koc_workflow_execution DROP CHECK chk_workflow_execution_status;
ALTER TABLE koc_workflow_execution ADD CONSTRAINT chk_workflow_execution_status CHECK (
  status IN (
    'PENDING', 'RUNNING', 'WAITING_SANDBOX', 'WAITING_APPROVAL', 'APPROVED', 'REJECTED',
    'SUCCEEDED', 'FAILED', 'CANCELLED'
  )
);

ALTER TABLE koc_workflow_node_execution DROP CHECK chk_workflow_node_status;
ALTER TABLE koc_workflow_node_execution ADD CONSTRAINT chk_workflow_node_status CHECK (
  status IN (
    'PENDING', 'RUNNING', 'WAITING_SANDBOX', 'WAITING_APPROVAL', 'SKIPPED',
    'SUCCEEDED', 'FAILED', 'CANCELLED'
  )
);
