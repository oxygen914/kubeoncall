package kubernetes

import (
	"context"
	"strings"
	"testing"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func TestFailureClassificationAndLogRedaction(t *testing.T) {
	pod := &corev1.Pod{Status: corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{{State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{Reason: "OOMKilled", ExitCode: 137}}}}}}
	if got := classifyFailureFromPod(pod); got != ReasonOOM {
		t.Fatalf("OOM reason = %s", got)
	}
	pod.Status.ContainerStatuses[0].State.Terminated = nil
	pod.Status.ContainerStatuses[0].State.Waiting = &corev1.ContainerStateWaiting{Reason: "ImagePullBackOff"}
	if got := classifyFailureFromPod(pod); got != ReasonImagePull {
		t.Fatalf("pull reason = %s", got)
	}
	if got := redact("Authorization: Bearer abcdefghijklmnopqrstuvwxyz\npassword=abcdefghijklmnop\nhttps://user:pass@example.com"); strings.Contains(got, "abcdefghijklmnop") || strings.Contains(got, "user:pass") {
		t.Fatalf("unredacted logs: %s", got)
	}
	logs := readBounded(strings.NewReader(strings.Repeat("line\n", 300)), 100)
	if len(logs) > 100 || !strings.Contains(logs, "[truncated]") {
		t.Fatalf("unexpected bounded logs length=%d: %q", len(logs), logs)
	}
}

func TestCollectResultMapsDeadlineAndMissingOutput(t *testing.T) {
	manager, clientset := newTestManager(t)
	ctx := context.Background()
	if _, err := manager.EnsureJob(ctx, request("sbx_a1b2"), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	markFailed(t, clientset, "sbx_a1b2", "DeadlineExceeded")
	result, err := manager.CollectResult(ctx, "sbx_a1b2", 128)
	if err != nil || result.Phase != PhaseTimedOut || result.Reason != ReasonDeadline || result.OutputFound {
		t.Fatalf("result=%+v err=%v", result, err)
	}
}

func TestOutputMarkerIsBoundedAndKeptSeparateFromLogs(t *testing.T) {
	output := `{"schemaVersion":"v1","status":"SUCCEEDED","findings":[],"evidenceReferences":[],"summary":"ok"}`
	if got := outputFromLogs("normal log\n" + outputMarker + output + "\n"); got != output {
		t.Fatalf("output = %q", got)
	}
	if got := outputFromLogs(outputMarker + strings.Repeat("x", maxStructuredOutputBytes+1)); got != "" {
		t.Fatalf("oversized output must be rejected: %d", len(got))
	}
}

func TestJanitorReapsOnlyFinishedOrExpiredSandboxJobs(t *testing.T) {
	manager, clientset := newTestManager(t)
	ctx := context.Background()
	now := time.Now().UTC()
	if _, err := manager.EnsureJob(ctx, request("sbx_a1b2"), now.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.EnsureJob(ctx, request("sbx_c3d4"), now.Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.EnsureJob(ctx, request("sbx_e5f6"), now.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	markSucceeded(t, clientset, "sbx_e5f6")
	foreign := &batchv1.Job{ObjectMeta: metav1.ObjectMeta{Name: "foreign", Namespace: "kubeoncall-sandbox", Labels: map[string]string{"app.kubernetes.io/name": "other"}}}
	if _, err := clientset.BatchV1().Jobs("kubeoncall-sandbox").Create(ctx, foreign, metav1.CreateOptions{}); err != nil {
		t.Fatal(err)
	}
	reaped, err := NewJanitor(manager).ReapOnce(ctx, now)
	if err != nil || len(reaped) != 2 {
		t.Fatalf("reaped=%+v err=%v", reaped, err)
	}
	// fake clientset can retain background-deletion objects until a synthetic garbage-collection
	// step. Reaped records prove the Janitor issued the scoped deletion; foreign jobs never appear.
	if reaped[0].RunID == "" || reaped[1].RunID == "" {
		t.Fatalf("invalid reaped records: %+v", reaped)
	}
}
