package kubernetes

import (
	"context"
	"fmt"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
)

// Janitor reaps orphaned sandbox Jobs: finished Jobs whose TTL has not yet fired and expired Jobs
// whose lease is past. It is the safety net for a controller that died between a Job reaching a
// terminal state and the backend persisting its result, and for Jobs whose TTLSecondsAfterFinished
// controller never ran. It never deletes a Job it cannot identify as a sandbox Job.
type Janitor struct {
	manager *Manager
	clock   func() time.Time
}

// NewJanitor constructs a Janitor over a Manager's namespace.
func NewJanitor(manager *Manager) *Janitor {
	return &Janitor{manager: manager, clock: time.Now}
}

// Reaped records one Job the janitor removed.
type Reaped struct {
	RunID   string
	Name    string
	Reason  string
	Failure error
}

// ReapOnce scans the sandbox namespace for finished or expired Jobs and deletes them, returning one
// Reaped entry per Job it acted on. A delete failure is recorded on the entry and does not abort
// the sweep, so a transient apiserver error on one Job does not strand the rest; the next sweep
// retries (cleanup-failure retry per §SBX-10).
func (janitor *Janitor) ReapOnce(ctx context.Context, now time.Time) ([]Reaped, error) {
	list, err := janitor.manager.client.BatchV1().Jobs(janitor.manager.namespace).List(ctx, metav1.ListOptions{
		LabelSelector: labels.Set{"app.kubernetes.io/name": "kubeoncall-sandbox"}.AsSelector().String(),
	})
	if err != nil {
		return nil, fmt.Errorf("list jobs: %w", err)
	}
	var reaped []Reaped
	for i := range list.Items {
		job := &list.Items[i]
		runID := job.Labels[RunIDLabel]
		reapedEntry := Reaped{RunID: runID, Name: job.Name}
		reason, eligible := janitor.eligibleForReap(job, now)
		if !eligible {
			continue
		}
		reapedEntry.Reason = reason
		policy := metav1.DeletePropagationBackground
		if err := janitor.manager.client.BatchV1().Jobs(janitor.manager.namespace).Delete(
			ctx, job.Name, metav1.DeleteOptions{PropagationPolicy: &policy}); err != nil {
			if isNotFound(err) {
				continue
			}
			reapedEntry.Failure = err
			reaped = append(reaped, reapedEntry)
			continue
		}
		reaped = append(reaped, reapedEntry)
	}
	return reaped, nil
}

// eligibleForReap returns why a Job may be reaped now: it is terminal, or its expiry annotation has
// passed. Non-terminal, unexpired Jobs are left for the reconciler.
func (janitor *Janitor) eligibleForReap(job *batchv1.Job, now time.Time) (string, bool) {
	if classify(job).IsTerminal() {
		return "finished", true
	}
	expiry := job.Annotations[ExpiryAnnotation]
	if expiry == "" {
		return "", false
	}
	expiresAt, err := time.Parse(time.RFC3339, expiry)
	if err != nil {
		return "", false
	}
	if now.After(expiresAt) {
		return "expired", true
	}
	return "", false
}
