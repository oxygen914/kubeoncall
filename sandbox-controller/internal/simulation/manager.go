// Package simulation owns remediation rehearsal namespaces in a separately configured,
// non-production Kubernetes cluster. It intentionally does not share credentials or a namespace
// with the ordinary sandbox Job manager.
package simulation

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	networkingv1 "k8s.io/api/networking/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
	sbxk8s "github.com/kubeoncall/sandbox-controller/internal/kubernetes"
)

const (
	RunIDLabel          = "sandbox.kubeoncall.io/run-id"
	SimulationLabel     = "sandbox.kubeoncall.io/simulation"
	SimulationModeLabel = "sandbox.kubeoncall.io/mode"
	SimulationMode      = "remediation_simulation"
	serviceAccountName  = "sandbox-simulation"
)

var namespacePrefix = regexp.MustCompile(`^[a-z0-9]([-a-z0-9]{0,42}[a-z0-9])?-$`)

// Manager materializes the minimum isolated simulation environment for a single Run. The passed
// client must point to a distinct non-production validation cluster; NewManager rejects ambiguous
// cluster identifiers instead of ever falling back to the controller's in-cluster client.
type Manager struct {
	client          kubernetes.Interface
	namespacePrefix string
	builder         jobs.Builder
	clusterID       string
}

func NewManager(client kubernetes.Interface, clusterID, prefix string, builder jobs.Builder) (*Manager, error) {
	if client == nil || !isNonProductionCluster(clusterID) || !namespacePrefixValid(prefix) {
		return nil, fmt.Errorf("simulation runtime requires a named non-production cluster and safe namespace prefix")
	}
	return &Manager{client: client, clusterID: clusterID, namespacePrefix: prefix, builder: builder}, nil
}

// EnsureJob creates an idempotent namespace-scoped rehearsal. Namespace preparation is all-or-
// nothing: a failed preparation removes only the namespace derived from this run before returning.
func (manager *Manager) EnsureJob(ctx context.Context, request jobs.Request, expiresAt time.Time) (sbxk8s.Status, error) {
	if request.Labels[SimulationModeLabel] != SimulationMode {
		return sbxk8s.Status{}, fmt.Errorf("simulation runtime rejected non-simulation request")
	}
	namespace := manager.NamespaceFor(request.RunID)
	created, err := manager.ensureNamespace(ctx, namespace, request.RunID)
	if err != nil {
		return sbxk8s.Status{}, err
	}
	if err := manager.prepareNamespace(ctx, namespace, request.RunID); err != nil {
		if created {
			_ = manager.deleteNamespace(ctx, namespace, request.RunID)
		}
		return sbxk8s.Status{}, fmt.Errorf("prepare simulation namespace: %w", err)
	}
	jobManager := manager.jobManager(namespace)
	status, err := jobManager.EnsureJob(ctx, request, expiresAt)
	if err != nil && created {
		_ = manager.deleteNamespace(ctx, namespace, request.RunID)
	}
	return status, err
}

func (manager *Manager) Status(ctx context.Context, runID string) (sbxk8s.Status, error) {
	namespace := manager.NamespaceFor(runID)
	if _, err := manager.client.CoreV1().Namespaces().Get(ctx, namespace, metav1.GetOptions{}); err != nil {
		if apierrors.IsNotFound(err) {
			return sbxk8s.Status{RunID: runID, Phase: sbxk8s.PhaseUnknown, Exists: false}, nil
		}
		return sbxk8s.Status{}, err
	}
	return manager.jobManager(namespace).Status(ctx, runID)
}

// Cancel is the cleanup boundary for every terminal, cancelled and expired simulation run. It
// deletes only a namespace whose immutable owner label matches the requested run.
func (manager *Manager) Cancel(ctx context.Context, runID string) (sbxk8s.Status, error) {
	namespace := manager.NamespaceFor(runID)
	if err := manager.deleteNamespace(ctx, namespace, runID); err != nil {
		return sbxk8s.Status{}, err
	}
	return sbxk8s.Status{RunID: runID, Phase: sbxk8s.PhaseCancelled, Exists: false}, nil
}

func (manager *Manager) CollectResult(ctx context.Context, runID string, logLimit int64) (sbxk8s.Result, error) {
	return manager.jobManager(manager.NamespaceFor(runID)).CollectResult(ctx, runID, logLimit)
}

// NamespaceFor is deterministic for status/cancel after controller restart, while a SHA-256
// suffix prevents user-controlled run IDs from overflowing Kubernetes DNS label limits.
func (manager *Manager) NamespaceFor(runID string) string {
	digest := sha256.Sum256([]byte(runID))
	return manager.namespacePrefix + hex.EncodeToString(digest[:])[:20]
}

func (manager *Manager) ensureNamespace(ctx context.Context, namespace, runID string) (bool, error) {
	labels := map[string]string{RunIDLabel: runID, SimulationLabel: "true", "sandbox.kubeoncall.io/cluster-id": manager.clusterID}
	_, err := manager.client.CoreV1().Namespaces().Create(ctx, &corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: namespace, Labels: labels}}, metav1.CreateOptions{})
	if err == nil {
		return true, nil
	}
	if !apierrors.IsAlreadyExists(err) {
		return false, fmt.Errorf("create namespace: %w", err)
	}
	existing, getErr := manager.client.CoreV1().Namespaces().Get(ctx, namespace, metav1.GetOptions{})
	if getErr != nil {
		return false, fmt.Errorf("read existing namespace: %w", getErr)
	}
	if existing.Labels[RunIDLabel] != runID || existing.Labels[SimulationLabel] != "true" {
		return false, fmt.Errorf("simulation namespace conflict")
	}
	return false, nil
}

func (manager *Manager) prepareNamespace(ctx context.Context, namespace, runID string) error {
	falseValue := false
	if _, err := manager.client.CoreV1().ServiceAccounts(namespace).Create(ctx, &corev1.ServiceAccount{
		ObjectMeta:                   metav1.ObjectMeta{Name: serviceAccountName, Labels: map[string]string{RunIDLabel: runID, SimulationLabel: "true"}},
		AutomountServiceAccountToken: &falseValue,
	}, metav1.CreateOptions{}); err != nil && !apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("create restricted service account: %w", err)
	}
	if _, err := manager.client.CoreV1().ConfigMaps(namespace).Create(ctx, &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "simulation-input", Labels: map[string]string{RunIDLabel: runID, SimulationLabel: "true"}},
		Data:       map[string]string{"cluster": manager.clusterID, "runId": runID, "resources": "REDACTED_BY_BACKEND"},
	}, metav1.CreateOptions{}); err != nil && !apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("create redacted simulation config: %w", err)
	}
	if _, err := manager.client.CoreV1().Secrets(namespace).Create(ctx, &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: "simulation-placeholders", Labels: map[string]string{RunIDLabel: runID, SimulationLabel: "true"}},
		Type:       corev1.SecretTypeOpaque,
		Data:       map[string][]byte{"placeholder": []byte("SIMULATION_ONLY_NOT_A_PRODUCTION_SECRET")},
	}, metav1.CreateOptions{}); err != nil && !apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("create secret placeholders: %w", err)
	}
	quota := &corev1.ResourceQuota{ObjectMeta: metav1.ObjectMeta{Name: "simulation-quota", Labels: map[string]string{RunIDLabel: runID, SimulationLabel: "true"}}, Spec: corev1.ResourceQuotaSpec{Hard: corev1.ResourceList{
		corev1.ResourcePods: resource.MustParse("4"), corev1.ResourceRequestsCPU: resource.MustParse("1"), corev1.ResourceRequestsMemory: resource.MustParse("1Gi"), corev1.ResourceLimitsCPU: resource.MustParse("1"), corev1.ResourceLimitsMemory: resource.MustParse("1Gi"),
	}}}
	if _, err := manager.client.CoreV1().ResourceQuotas(namespace).Create(ctx, quota, metav1.CreateOptions{}); err != nil && !apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("create simulation quota: %w", err)
	}
	if _, err := manager.client.NetworkingV1().NetworkPolicies(namespace).Create(ctx, &networkingv1.NetworkPolicy{ObjectMeta: metav1.ObjectMeta{Name: "default-deny", Labels: map[string]string{RunIDLabel: runID, SimulationLabel: "true"}}, Spec: networkingv1.NetworkPolicySpec{PodSelector: metav1.LabelSelector{}, PolicyTypes: []networkingv1.PolicyType{networkingv1.PolicyTypeIngress, networkingv1.PolicyTypeEgress}}}, metav1.CreateOptions{}); err != nil && !apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("create simulation network policy: %w", err)
	}
	return nil
}

func (manager *Manager) deleteNamespace(ctx context.Context, namespace, runID string) error {
	existing, err := manager.client.CoreV1().Namespaces().Get(ctx, namespace, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("read simulation namespace: %w", err)
	}
	if existing.Labels[RunIDLabel] != runID || existing.Labels[SimulationLabel] != "true" {
		return fmt.Errorf("refusing to delete namespace not owned by simulation run")
	}
	if err := manager.client.CoreV1().Namespaces().Delete(ctx, namespace, metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete simulation namespace: %w", err)
	}
	return nil
}

func (manager *Manager) jobManager(namespace string) *sbxk8s.Manager {
	builder := manager.builder
	builder.Namespace = namespace
	builder.ServiceAccountName = serviceAccountName
	return sbxk8s.NewManager(manager.client, namespace, builder)
}

func namespacePrefixValid(prefix string) bool {
	return namespacePrefix.MatchString(prefix) && len(prefix) <= 45
}

func isNonProductionCluster(clusterID string) bool {
	id := strings.ToLower(strings.TrimSpace(clusterID))
	return strings.HasPrefix(id, "nonprod-") || strings.HasPrefix(id, "staging-") || strings.HasPrefix(id, "validation-")
}
