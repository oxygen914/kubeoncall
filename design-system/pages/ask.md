# Ask page

The Ask workspace follows `design-system/MASTER.md` and adds these page-specific rules:

- Use a two-column desktop layout: the conversation is primary; governed execution context is
  secondary and remains visible beside the answer.
- Keep an empty answer workspace visible before the first question. Do not make the page look like
  a plain form whose output appears unexpectedly below the fold.
- Render user questions and AI answers as distinct transcript turns. Label generated content as
  `KubeOnCall AI` and include a verification notice.
- Preserve `sessionId` across follow-up questions so the visible interaction matches backend
  conversation memory behavior.
- Show answer, execution status, task plan, risk reasons, approval state, and raw evidence in
  progressive layers. Raw JSON is collapsed by default.
- Enter sends; Shift + Enter inserts a new line. Disable duplicate submission while pending and
  announce request failures with `role="alert"` plus a retry path.
- Read-only diagnosis may run automatically. Restart, scaling, patching, and other state changes
  must display the approval boundary. Do not link a synchronous Ask result to the MySQL approval
  console until the page submits through the durable execution workflow.
- Do not imply token streaming until the backend exposes a streaming contract.
