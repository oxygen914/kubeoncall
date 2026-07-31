package simulation

import (
	"context"
	"errors"
	"testing"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
	sbxk8s "github.com/kubeoncall/sandbox-controller/internal/kubernetes"
)

const simulationImage = "registry/simulation@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

func newTestManager(t *testing.T) (*Manager, *fake.Clientset) {
	t.Helper()
	client := fake.NewSimpleClientset()
	manager, err := NewManager(client, "validation-kind", "koc-sim-", jobs.Builder{
		Tools: map[string]jobs.Tool{"remediation-simulation:v1": {
			ID: "remediation-simulation", Version: "v1", Image: simulationImage, Entrypoint: []string{"/simulate"},
			Runtime: jobs.RuntimeFixedDiagnostic, NetworkEgressPolicy: jobs.NetworkEgressDenyAll,
			Limits: jobs.Limits{CPUMilli: 250, MemoryMiB: 256, EphemeralMiB: 256, TimeoutSeconds: 30, TTLSeconds: 3600},
		}},
		Ceiling: jobs.Limits{CPUMilli: 500, MemoryMiB: 512, EphemeralMiB: 512, TimeoutSeconds: 60, TTLSeconds: 3600},
	})
	if err != nil {
		t.Fatalf("new manager: %v", err)
	}
	return manager, client
}

func simulationRequest(runID string) jobs.Request {
	return jobs.Request{RunID: runID, ToolID: "remediation-simulation", ToolVersion: "v1", InputArtifactURI: "minio://sandbox/sbx/inputs/simulation.json", Labels: map[string]string{SimulationModeLabel: SimulationMode}}
}

func TestEnsureJobCreatesRestrictedTemporaryEnvironment(t *testing.T) {
	manager, client := newTestManager(t)
	request := simulationRequest("sbx_a1b2")
	status, err := manager.EnsureJob(context.Background(), request, time.Now().Add(time.Hour))
	if err != nil || !status.Exists || status.Phase != sbxk8s.PhasePending {
		t.Fatalf("ensure = %+v, %v", status, err)
	}
	namespace := manager.NamespaceFor(request.RunID)
	if namespace == request.RunID || len(namespace) > 63 {
		t.Fatalf("namespace must be bounded and derived, got %q", namespace)
	}
	serviceAccount, err := client.CoreV1().ServiceAccounts(namespace).Get(context.Background(), serviceAccountName, metav1.GetOptions{})
	if err != nil || serviceAccount.AutomountServiceAccountToken == nil || *serviceAccount.AutomountServiceAccountToken {
		t.Fatalf("simulation service account must disable token mounting: %#v, %v", serviceAccount, err)
	}
	config, err := client.CoreV1().ConfigMaps(namespace).Get(context.Background(), "simulation-input", metav1.GetOptions{})
	if err != nil || config.Data["resources"] != "REDACTED_BY_BACKEND" {
		t.Fatalf("redacted config = %#v, %v", config, err)
	}
	secret, err := client.CoreV1().Secrets(namespace).Get(context.Background(), "simulation-placeholders", metav1.GetOptions{})
	if err != nil || string(secret.Data["placeholder"]) != "SIMULATION_ONLY_NOT_A_PRODUCTION_SECRET" {
		t.Fatalf("placeholder secret = %#v, %v", secret, err)
	}
	if _, err := client.CoreV1().ResourceQuotas(namespace).Get(context.Background(), "simulation-quota", metav1.GetOptions{}); err != nil {
		t.Fatalf("simulation quota missing: %v", err)
	}
	policy, err := client.NetworkingV1().NetworkPolicies(namespace).Get(context.Background(), "default-deny", metav1.GetOptions{})
	if err != nil || len(policy.Spec.PolicyTypes) != 2 {
		t.Fatalf("default-deny policy = %#v, %v", policy, err)
	}
	created, err := client.BatchV1().Jobs(namespace).List(context.Background(), metav1.ListOptions{})
	if err != nil || len(created.Items) != 1 {
		t.Fatalf("created simulation jobs = %#v, %v", created, err)
	}
	pod := created.Items[0].Spec.Template.Spec
	if pod.ServiceAccountName != serviceAccountName || pod.AutomountServiceAccountToken == nil || *pod.AutomountServiceAccountToken {
		t.Fatalf("simulation job identity must be restricted: %#v", pod)
	}
}

func TestEnsureJobIsIdempotentAndRejectsNamespaceCollision(t *testing.T) {
	manager, client := newTestManager(t)
	request := simulationRequest("sbx_a1b2")
	if _, err := manager.EnsureJob(context.Background(), request, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("initial ensure: %v", err)
	}
	if _, err := manager.EnsureJob(context.Background(), request, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("idempotent ensure: %v", err)
	}
	jobs, _ := client.BatchV1().Jobs(manager.NamespaceFor(request.RunID)).List(context.Background(), metav1.ListOptions{})
	if len(jobs.Items) != 1 {
		t.Fatalf("expected one job after replay, got %d", len(jobs.Items))
	}

	conflictRun := "sbx_b2c3"
	conflictNamespace := manager.NamespaceFor(conflictRun)
	if _, err := client.CoreV1().Namespaces().Create(context.Background(), &corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: conflictNamespace, Labels: map[string]string{RunIDLabel: "sbx_other"}}}, metav1.CreateOptions{}); err != nil {
		t.Fatalf("create conflict namespace: %v", err)
	}
	if _, err := manager.EnsureJob(context.Background(), simulationRequest(conflictRun), time.Now().Add(time.Hour)); err == nil {
		t.Fatal("expected namespace collision rejection")
	}
}

func TestCancelDeletesOnlyOwnedNamespaceAndStatusIsAbsent(t *testing.T) {
	manager, client := newTestManager(t)
	request := simulationRequest("sbx_c3d4")
	if _, err := manager.EnsureJob(context.Background(), request, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("ensure: %v", err)
	}
	if _, err := manager.Cancel(context.Background(), request.RunID); err != nil {
		t.Fatalf("cancel: %v", err)
	}
	if _, err := client.CoreV1().Namespaces().Get(context.Background(), manager.NamespaceFor(request.RunID), metav1.GetOptions{}); !apierrors.IsNotFound(err) {
		t.Fatalf("namespace should be deleted, err=%v", err)
	}
	status, err := manager.Status(context.Background(), request.RunID)
	if err != nil || status.Exists || status.Phase != sbxk8s.PhaseUnknown {
		t.Fatalf("status after cleanup = %+v, %v", status, err)
	}
}

func TestPreparationFailureCleansNewNamespaceAndCleanupFailureIsVisible(t *testing.T) {
	manager, client := newTestManager(t)
	client.PrependReactor("create", "configmaps", func(k8stesting.Action) (bool, runtime.Object, error) { return true, nil, errors.New("quota exhausted") })
	request := simulationRequest("sbx_d4e5")
	if _, err := manager.EnsureJob(context.Background(), request, time.Now().Add(time.Hour)); err == nil {
		t.Fatal("expected preparation failure")
	}
	if _, err := client.CoreV1().Namespaces().Get(context.Background(), manager.NamespaceFor(request.RunID), metav1.GetOptions{}); !apierrors.IsNotFound(err) {
		t.Fatalf("failed preparation must remove new namespace, err=%v", err)
	}

	client = fake.NewSimpleClientset(&corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: manager.NamespaceFor(request.RunID), Labels: map[string]string{RunIDLabel: request.RunID, SimulationLabel: "true"}}})
	manager.client = client
	client.PrependReactor("delete", "namespaces", func(k8stesting.Action) (bool, runtime.Object, error) {
		return true, nil, errors.New("apiserver unavailable")
	})
	if _, err := manager.Cancel(context.Background(), request.RunID); err == nil {
		t.Fatal("cleanup failure must be returned for retry")
	}
}

func TestStatusReportsStartupFailure(t *testing.T) {
	manager, client := newTestManager(t)
	request := simulationRequest("sbx_e5f6")
	if _, err := manager.EnsureJob(context.Background(), request, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("ensure: %v", err)
	}
	namespace := manager.NamespaceFor(request.RunID)
	job, err := client.BatchV1().Jobs(namespace).List(context.Background(), metav1.ListOptions{})
	if err != nil || len(job.Items) != 1 {
		t.Fatalf("job: %#v, %v", job, err)
	}
	failed := job.Items[0]
	failed.Status.Conditions = []batchv1.JobCondition{{Type: batchv1.JobFailed, Status: corev1.ConditionTrue, Reason: "BackoffLimitExceeded"}}
	if _, err := client.BatchV1().Jobs(namespace).UpdateStatus(context.Background(), &failed, metav1.UpdateOptions{}); err != nil {
		t.Fatalf("mark failed: %v", err)
	}
	status, err := manager.Status(context.Background(), request.RunID)
	if err != nil || status.Phase != sbxk8s.PhaseFailed {
		t.Fatalf("startup failure status = %+v, %v", status, err)
	}
}

func TestNewManagerRejectsProductionOrUnsafeConfiguration(t *testing.T) {
	client := fake.NewSimpleClientset()
	if _, err := NewManager(client, "production-main", "koc-sim-", jobs.Builder{}); err == nil {
		t.Fatal("production cluster identifier must be rejected")
	}
	if _, err := NewManager(client, "validation-kind", "Bad/", jobs.Builder{}); err == nil {
		t.Fatal("unsafe namespace prefix must be rejected")
	}
}
