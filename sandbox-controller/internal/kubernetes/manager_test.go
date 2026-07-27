package kubernetes

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
)

const (
	testImage = "registry/pod-inspect@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
)

func newTestManager(t *testing.T) (*Manager, *fake.Clientset) {
	t.Helper()
	clientset := fake.NewSimpleClientset()
	builder := jobs.Builder{
		Namespace: "kubeoncall-sandbox",
		Tools: map[string]jobs.Tool{
			"pod-inspect:v1": {ID: "pod-inspect", Version: "v1", Image: testImage, Entrypoint: []string{"/tool"}},
		},
		Ceiling: jobs.Limits{CPUMilli: 500, MemoryMiB: 512, EphemeralMiB: 512, TimeoutSeconds: 300, TTLSeconds: 3600},
	}
	return NewManager(clientset, "kubeoncall-sandbox", builder), clientset
}

func request(runID string) jobs.Request {
	return jobs.Request{RunID: runID, ToolID: "pod-inspect", ToolVersion: "v1", InputArtifactURI: "minio://sandbox/sbx_abcd/inputs/request.json"}
}

func TestEnsureJobCreatesAndIsIdempotentOnReplay(t *testing.T) {
	manager, clientset := newTestManager(t)
	ctx := context.Background()
	expires := time.Now().Add(time.Hour)

	first, err := manager.EnsureJob(ctx, request("sbx_abcd"), expires)
	if err != nil {
		t.Fatalf("first create: %v", err)
	}
	if !first.Exists || first.Phase != PhasePending {
		t.Fatalf("first status = %+v", first)
	}

	// A replayed dispatch must not create a second Job.
	second, err := manager.EnsureJob(ctx, request("sbx_abcd"), expires)
	if err != nil {
		t.Fatalf("replay create: %v", err)
	}
	if !second.Exists {
		t.Fatalf("replay should report existing job")
	}

	jobs, err := clientset.BatchV1().Jobs("kubeoncall-sandbox").List(ctx, metav1.ListOptions{})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(jobs.Items) != 1 {
		t.Fatalf("expected exactly 1 job, got %d", len(jobs.Items))
	}
}

func TestEnsureJobRejectsInvalidRunID(t *testing.T) {
	manager, _ := newTestManager(t)
	_, err := manager.EnsureJob(context.Background(), request("sbx_bad/hostpath"), time.Now().Add(time.Hour))
	if err != ErrInvalidRunID && err == nil {
		t.Fatalf("expected invalid run id error, got %v", err)
	}
}

func TestStatusNormalizesPhases(t *testing.T) {
	manager, clientset := newTestManager(t)
	ctx := context.Background()

	// Created but not started -> PENDING.
	if _, err := manager.EnsureJob(ctx, request("sbx_a1b2"), time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("create: %v", err)
	}
	pending, err := manager.Status(ctx, "sbx_a1b2")
	if err != nil {
		t.Fatalf("status pending: %v", err)
	}
	if pending.Phase != PhasePending {
		t.Fatalf("pending phase = %s", pending.Phase)
	}

	// A running job -> RUNNING.
	if _, err := manager.EnsureJob(ctx, request("sbx_c3d4"), time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("create: %v", err)
	}
	markRunning(t, clientset, "sbx_c3d4")
	running, err := manager.Status(ctx, "sbx_c3d4")
	if err != nil {
		t.Fatalf("status running: %v", err)
	}
	if running.Phase != PhaseRunning {
		t.Fatalf("running phase = %s", running.Phase)
	}

	// A completed job -> SUCCEEDED.
	if _, err := manager.EnsureJob(ctx, request("sbx_e5f6"), time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("create: %v", err)
	}
	markSucceeded(t, clientset, "sbx_e5f6")
	done, err := manager.Status(ctx, "sbx_e5f6")
	if err != nil {
		t.Fatalf("status done: %v", err)
	}
	if done.Phase != PhaseSucceeded {
		t.Fatalf("done phase = %s", done.Phase)
	}

	// Unknown run -> not exists.
	missing, err := manager.Status(ctx, "sbx_e7f8")
	if err != nil {
		t.Fatalf("status missing: %v", err)
	}
	if missing.Exists || missing.Phase != PhaseUnknown {
		t.Fatalf("missing status = %+v", missing)
	}
}

func TestStatusClassifiesTimeoutVsFailure(t *testing.T) {
	manager, clientset := newTestManager(t)
	ctx := context.Background()

	if _, err := manager.EnsureJob(ctx, request("sbx_a7b8"), time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("create: %v", err)
	}
	markFailed(t, clientset, "sbx_a7b8", "DeadlineExceeded")
	timed, err := manager.Status(ctx, "sbx_a7b8")
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	if timed.Phase != PhaseTimedOut {
		t.Fatalf("timeout phase = %s", timed.Phase)
	}

	if _, err := manager.EnsureJob(ctx, request("sbx_c9d0"), time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("create: %v", err)
	}
	markFailed(t, clientset, "sbx_c9d0", "BackoffLimitExceeded")
	failed, err := manager.Status(ctx, "sbx_c9d0")
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	if failed.Phase != PhaseFailed {
		t.Fatalf("failed phase = %s", failed.Phase)
	}
}

func TestCancelIsIdempotentAndAcceptsMissingJob(t *testing.T) {
	manager, _ := newTestManager(t)
	ctx := context.Background()

	// Canceling a run that was never created is a success.
	missing, err := manager.Cancel(ctx, "sbx_c5d6")
	if err != nil {
		t.Fatalf("cancel missing: %v", err)
	}
	if missing.Phase != PhaseCancelled {
		t.Fatalf("missing cancel phase = %s", missing.Phase)
	}

	// Cancel a created job, then cancel again.
	if _, err := manager.EnsureJob(ctx, request("sbx_e1f2"), time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("create: %v", err)
	}
	first, err := manager.Cancel(ctx, "sbx_e1f2")
	if err != nil {
		t.Fatalf("cancel: %v", err)
	}
	if first.Phase != PhaseCancelled {
		t.Fatalf("cancel phase = %s", first.Phase)
	}
	second, err := manager.Cancel(ctx, "sbx_e1f2")
	if err != nil {
		t.Fatalf("re-cancel: %v", err)
	}
	if second.Phase != PhaseCancelled {
		t.Fatalf("re-cancel phase = %s", second.Phase)
	}
}

func TestEnsureJobEmitsHardenedSecurityContext(t *testing.T) {
	manager, clientset := newTestManager(t)
	ctx := context.Background()
	if _, err := manager.EnsureJob(ctx, request("sbx_a3b4"), time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("create: %v", err)
	}
	list, err := clientset.BatchV1().Jobs("kubeoncall-sandbox").List(ctx, metav1.ListOptions{})
	if err != nil || len(list.Items) != 1 {
		t.Fatalf("list: %v %d", err, len(list.Items))
	}
	job := list.Items[0]
	pod := job.Spec.Template
	if pod.Spec.HostNetwork {
		t.Fatal("hostNetwork must be false")
	}
	if pod.Spec.RestartPolicy != corev1.RestartPolicyNever {
		t.Fatalf("restart policy = %s", pod.Spec.RestartPolicy)
	}
	if pod.Spec.SecurityContext.SeccompProfile.Type != corev1.SeccompProfileTypeRuntimeDefault {
		t.Fatal("seccomp RuntimeDefault required")
	}
	container := pod.Spec.Containers[0]
	if container.SecurityContext == nil || !*container.SecurityContext.RunAsNonRoot || *container.SecurityContext.AllowPrivilegeEscalation {
		t.Fatal("hardened container security context required")
	}
	if job.Spec.TTLSecondsAfterFinished == nil || *job.Spec.TTLSecondsAfterFinished != 3600 {
		t.Fatal("TTL must be set")
	}
	if job.Spec.ActiveDeadlineSeconds == nil || *job.Spec.ActiveDeadlineSeconds != 300 {
		t.Fatal("active deadline (timeout) must be set")
	}
	if pod.Spec.AutomountServiceAccountToken == nil || *pod.Spec.AutomountServiceAccountToken {
		t.Fatal("sandbox jobs must not mount a ServiceAccount token")
	}
	if len(container.VolumeMounts) != 1 || container.VolumeMounts[0].MountPath != "/sandbox" || container.VolumeMounts[0].ReadOnly {
		t.Fatalf("only a writable sandbox workspace may be mounted: %#v", container.VolumeMounts)
	}
	for _, env := range container.Env {
		if strings.Contains(strings.ToLower(env.Name), "credential") || strings.Contains(strings.ToLower(env.Name), "secret") || strings.Contains(strings.ToLower(env.Name), "token") {
			t.Fatalf("sandbox job received credential-like env %s", env.Name)
		}
	}
	if job.Labels[RunIDLabel] != "sbx_a3b4" || job.Labels[ToolVersionLabel] != "v1" {
		t.Fatalf("job labels = %v", job.Labels)
	}
	// Expiry lives in an annotation because RFC3339 colons are illegal in label values; assert it is
	// present and that no label carries a colon-bearing value a real apiserver would reject.
	if job.Annotations[ExpiryAnnotation] == "" {
		t.Fatalf("job expiry annotation missing: %v", job.Annotations)
	}
	for key, value := range job.Labels {
		if strings.Contains(value, ":") {
			t.Fatalf("label %s=%s contains a colon, illegal in Kubernetes label values", key, value)
		}
	}
}

// markRunning flips a job into the running state by setting Active and a start time.
func markRunning(t *testing.T, clientset *fake.Clientset, runID string) {
	t.Helper()
	job := fetchJob(t, clientset, runID)
	now := metav1.Now()
	job.Status.StartTime = &now
	job.Status.Active = 1
	if _, err := clientset.BatchV1().Jobs("kubeoncall-sandbox").UpdateStatus(context.Background(), job, metav1.UpdateOptions{}); err != nil {
		t.Fatalf("update status: %v", err)
	}
}

func markSucceeded(t *testing.T, clientset *fake.Clientset, runID string) {
	t.Helper()
	job := fetchJob(t, clientset, runID)
	now := metav1.Now()
	job.Status.StartTime = &now
	job.Status.CompletionTime = &now
	job.Status.Succeeded = 1
	job.Status.Conditions = []batchv1.JobCondition{{Type: batchv1.JobComplete, Status: corev1.ConditionTrue}}
	if _, err := clientset.BatchV1().Jobs("kubeoncall-sandbox").UpdateStatus(context.Background(), job, metav1.UpdateOptions{}); err != nil {
		t.Fatalf("update status: %v", err)
	}
}

func markFailed(t *testing.T, clientset *fake.Clientset, runID, reason string) {
	t.Helper()
	job := fetchJob(t, clientset, runID)
	now := metav1.Now()
	job.Status.StartTime = &now
	job.Status.CompletionTime = &now
	job.Status.Failed = 1
	job.Status.Conditions = []batchv1.JobCondition{{Type: batchv1.JobFailed, Status: corev1.ConditionTrue, Reason: reason}}
	if _, err := clientset.BatchV1().Jobs("kubeoncall-sandbox").UpdateStatus(context.Background(), job, metav1.UpdateOptions{}); err != nil {
		t.Fatalf("update status: %v", err)
	}
}

func fetchJob(t *testing.T, clientset *fake.Clientset, runID string) *batchv1.Job {
	t.Helper()
	list, err := clientset.BatchV1().Jobs("kubeoncall-sandbox").List(context.Background(), metav1.ListOptions{})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	for i := range list.Items {
		if list.Items[i].Labels[RunIDLabel] == runID {
			return &list.Items[i]
		}
	}
	t.Fatalf("job for run %s not found", runID)
	return nil
}
