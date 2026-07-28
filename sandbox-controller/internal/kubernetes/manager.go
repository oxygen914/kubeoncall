// Package kubernetes owns the one-shot Job lifecycle the controller manages on behalf of the
// backend. It is the only component that holds Kubernetes write credentials: the backend never
// creates or deletes Jobs directly. Every operation is idempotent and keyed by the run id label,
// so a retried dispatch or a controller restart never produces a second Job for the same run.
package kubernetes

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/client-go/kubernetes"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
)

// RunIDLabel is the label that binds a Job to exactly one sandbox run. Selecting on it is the
// idempotency key for create and the scoping key for cancel/status.
const RunIDLabel = "sandbox.kubeoncall.io/run-id"

// ToolVersionLabel labels the Job for reconciliation; ExpiryAnnotation stores the RFC3339 expiry as
// an annotation because label values forbid the colons in a timestamp. The janitor reads the
// annotation to decide when a finished Job may be reaped.
const (
	ToolVersionLabel = "sandbox.kubeoncall.io/tool-version"
	ExpiryAnnotation = "sandbox.kubeoncall.io/expires-at"
)

// Status is the controller's normalized view of a Job, decoupled from Kubernetes types so the
// backend reconciler never sees raw Pod conditions.
type Status struct {
	RunID     string
	Phase     Phase
	StartTime *time.Time
	EndTime   *time.Time
	// FailedPods reports terminated pods that did not succeed, for OOM/ImagePull distinction.
	FailedPods []PodFailure
	// Exists is false when no Job for the run id is present (never created or already reaped).
	Exists bool
}

// Phase maps Kubernetes Job/Pod state onto the sandbox run lifecycle.
type Phase string

const (
	PhasePending   Phase = "PENDING"
	PhaseRunning   Phase = "RUNNING"
	PhaseSucceeded Phase = "SUCCEEDED"
	PhaseFailed    Phase = "FAILED"
	PhaseTimedOut  Phase = "TIMED_OUT"
	PhaseCancelled Phase = "CANCELLED"
	PhaseUnknown   Phase = "UNKNOWN"
)

// PodFailure captures the termination reason of a failed pod for error classification.
type PodFailure struct {
	Name   string
	Reason string
}

// Manager creates, observes and cancels one-shot sandbox Jobs. It depends only on
// kubernetes.Interface so tests inject a fake clientset.
type Manager struct {
	client    kubernetes.Interface
	namespace string
	builder   jobs.Builder
	clock     func() time.Time
}

// NewManager constructs a Manager bound to the sandbox namespace and tool catalog.
func NewManager(client kubernetes.Interface, namespace string, builder jobs.Builder) *Manager {
	return &Manager{client: client, namespace: namespace, builder: builder, clock: time.Now}
}

// EnsureJob creates the Job for a run idempotently. If a Job already exists for the run it is
// returned as-is — a retried dispatch never creates a second Job. The Job carries run-id, tool and
// expiry labels so a controller restart or janitor can recover scope from the cluster state alone.
func (manager *Manager) EnsureJob(ctx context.Context, request jobs.Request, expiresAt time.Time) (Status, error) {
	if err := validateRequest(request); err != nil {
		return Status{}, err
	}
	existing, err := manager.findJob(ctx, request.RunID)
	if err != nil {
		return Status{}, err
	}
	if existing != nil {
		// Idempotent replay: return the existing Job's status rather than creating a duplicate.
		return manager.statusFromJob(existing), nil
	}
	spec, err := manager.builder.Build(request)
	if err != nil {
		return Status{}, fmt.Errorf("build job: %w", err)
	}
	job := manager.jobFromSpec(spec, request, expiresAt)
	created, err := manager.client.BatchV1().Jobs(manager.namespace).Create(ctx, job, metav1.CreateOptions{})
	if err != nil {
		// A concurrent create from another controller replica surfaces as an AlreadyExists; fetch
		// the winner and return its status instead of erroring.
		if errors.Is(err, ErrAlreadyExists) || isAlreadyExists(err) {
			winner, fetchErr := manager.findJob(ctx, request.RunID)
			if fetchErr != nil {
				return Status{}, fetchErr
			}
			if winner == nil {
				return Status{}, fmt.Errorf("job reported already-exists but not found: %w", err)
			}
			return manager.statusFromJob(winner), nil
		}
		return Status{}, fmt.Errorf("create job: %w", err)
	}
	return manager.statusFromJob(created), nil
}

// Status returns the normalized status of the Job for a run id. If no Job exists the returned
// Status has Exists=false and Phase=PhaseUnknown.
func (manager *Manager) Status(ctx context.Context, runID string) (Status, error) {
	if !runIDRegex(runID) {
		return Status{}, ErrInvalidRunID
	}
	job, err := manager.findJob(ctx, runID)
	if err != nil {
		return Status{}, err
	}
	if job == nil {
		return Status{RunID: runID, Phase: PhaseUnknown, Exists: false}, nil
	}
	return manager.statusFromJob(job), nil
}

// Cancel deletes the Job for a run id, idempotently. A missing Job is reported as a successful
// cancel so a late retry after the Job already reaped does not surface an error. Background
// propagation lets the kubelet reap pods without blocking the call.
func (manager *Manager) Cancel(ctx context.Context, runID string) (Status, error) {
	if !runIDRegex(runID) {
		return Status{}, ErrInvalidRunID
	}
	job, err := manager.findJob(ctx, runID)
	if err != nil {
		return Status{}, err
	}
	if job == nil {
		return Status{RunID: runID, Phase: PhaseCancelled, Exists: false}, nil
	}
	policy := metav1.DeletePropagationBackground
	err = manager.client.BatchV1().Jobs(manager.namespace).Delete(
		ctx, job.Name, metav1.DeleteOptions{PropagationPolicy: &policy})
	if err != nil {
		if isNotFound(err) {
			return Status{RunID: runID, Phase: PhaseCancelled, Exists: false}, nil
		}
		return Status{}, fmt.Errorf("delete job: %w", err)
	}
	return Status{RunID: runID, Phase: PhaseCancelled, Exists: true}, nil
}

func (manager *Manager) findJob(ctx context.Context, runID string) (*batchv1.Job, error) {
	list, err := manager.client.BatchV1().Jobs(manager.namespace).List(ctx, metav1.ListOptions{
		LabelSelector: labels.Set{RunIDLabel: runID}.AsSelector().String(),
		Limit:         2,
	})
	if err != nil {
		return nil, fmt.Errorf("list jobs: %w", err)
	}
	if len(list.Items) == 0 {
		return nil, nil
	}
	if len(list.Items) > 1 {
		// Should be impossible given the run-id uniqueness label, but never silently pick one.
		return nil, fmt.Errorf("multiple jobs for run %s", runID)
	}
	return &list.Items[0], nil
}

func (manager *Manager) jobFromSpec(spec jobs.JobSpec, request jobs.Request, expiresAt time.Time) *batchv1.Job {
	labels := spec.Labels
	labels[ToolVersionLabel] = request.ToolVersion
	annotations := map[string]string{ExpiryAnnotation: expiresAt.UTC().Format(time.RFC3339)}
	ttl := int32(spec.Limits.TTLSeconds)
	backoff := int32(0) // one-shot: never retry a failed sandbox run automatically.
	completions := int32(1)
	parallelism := int32(1)
	activeDeadline := int64(spec.Limits.TimeoutSeconds)
	automount := false
	return &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{
			Name:        spec.Name,
			Namespace:   spec.Namespace,
			Labels:      labels,
			Annotations: annotations,
		},
		Spec: batchv1.JobSpec{
			Completions:             &completions,
			Parallelism:             &parallelism,
			BackoffLimit:            &backoff,
			TTLSecondsAfterFinished: &ttl,
			ActiveDeadlineSeconds:   &activeDeadline,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec: corev1.PodSpec{
					ServiceAccountName:            spec.ServiceAccountName,
					RestartPolicy:                 corev1.RestartPolicyNever,
					AutomountServiceAccountToken:  &automount,
					HostNetwork:                   spec.HostNetwork,
					SecurityContext:               podSecurityContext(spec),
					TerminationGracePeriodSeconds: ptrInt64(5),
					Volumes: []corev1.Volume{{
						Name:         "sandbox-workspace",
						VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}},
					}},
					Containers: []corev1.Container{{
						Name:    "sandbox",
						Image:   spec.Image,
						Command: spec.Command,
						Env:     environment(spec.Environment),
						VolumeMounts: []corev1.VolumeMount{{
							Name: "sandbox-workspace", MountPath: "/sandbox",
						}},
						Resources: containerResources(spec),
						SecurityContext: &corev1.SecurityContext{
							RunAsNonRoot:             ptrBool(spec.Security.RunAsNonRoot),
							ReadOnlyRootFilesystem:   ptrBool(spec.Security.ReadOnlyRootFilesystem),
							AllowPrivilegeEscalation: ptrBool(spec.Security.AllowPrivilegeEscalation),
							Capabilities:             &corev1.Capabilities{Drop: dropCapabilities(spec)},
						},
					}},
				},
			},
		},
	}
}

func environment(values map[string]string) []corev1.EnvVar {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]corev1.EnvVar, 0, len(keys))
	for _, key := range keys {
		result = append(result, corev1.EnvVar{Name: key, Value: values[key]})
	}
	return result
}

func (manager *Manager) statusFromJob(job *batchv1.Job) Status {
	status := Status{RunID: job.Labels[RunIDLabel], Exists: true}
	if job.Status.StartTime != nil {
		start := job.Status.StartTime.Time
		status.StartTime = &start
	}
	if job.Status.CompletionTime != nil {
		end := job.Status.CompletionTime.Time
		status.EndTime = &end
	}
	status.Phase = classify(job)
	if job.Status.Failed > 0 {
		status.FailedPods = []PodFailure{{Name: "", Reason: "FAILED"}}
	}
	return status
}

// classify maps the Job's condition and active/succeeded/failed counts onto a sandbox Phase.
func classify(job *batchv1.Job) Phase {
	for _, condition := range job.Status.Conditions {
		switch condition.Type {
		case batchv1.JobComplete:
			if condition.Status == corev1.ConditionTrue {
				return PhaseSucceeded
			}
		case batchv1.JobFailed:
			if condition.Status == corev1.ConditionTrue {
				if isDeadlineExceeded(job) {
					return PhaseTimedOut
				}
				if isCancelled(job) {
					return PhaseCancelled
				}
				return PhaseFailed
			}
		case batchv1.JobSuspended:
			if condition.Status == corev1.ConditionTrue {
				return PhaseCancelled
			}
		}
	}
	if job.Status.Active > 0 {
		return PhaseRunning
	}
	if job.Status.Succeeded > 0 {
		return PhaseSucceeded
	}
	return PhasePending
}

func isDeadlineExceeded(job *batchv1.Job) bool {
	for _, condition := range job.Status.Conditions {
		if condition.Type == batchv1.JobFailed && condition.Status == corev1.ConditionTrue && condition.Reason == "DeadlineExceeded" {
			return true
		}
	}
	return false
}

// isCancelled treats an explicit deletion (no remaining active pods, failure without deadline) as a
// cancel only when the Job has a deletion timestamp; otherwise it is a genuine tool failure.
func isCancelled(job *batchv1.Job) bool {
	return job.DeletionTimestamp != nil
}

func podSecurityContext(spec jobs.JobSpec) *corev1.PodSecurityContext {
	return &corev1.PodSecurityContext{
		RunAsNonRoot: ptrBool(spec.Security.RunAsNonRoot),
		SeccompProfile: &corev1.SeccompProfile{
			Type: corev1.SeccompProfileTypeRuntimeDefault,
		},
	}
}

func containerResources(spec jobs.JobSpec) corev1.ResourceRequirements {
	resources := corev1.ResourceList{
		corev1.ResourceCPU:              milliCPU(spec.Limits.CPUMilli),
		corev1.ResourceMemory:           mebiBytes(spec.Limits.MemoryMiB),
		corev1.ResourceEphemeralStorage: mebiBytes(spec.Limits.EphemeralMiB),
	}
	return corev1.ResourceRequirements{Requests: resources.DeepCopy(), Limits: resources}
}

func dropCapabilities(spec jobs.JobSpec) []corev1.Capability {
	caps := make([]corev1.Capability, 0, len(spec.Security.DropCapabilities))
	for _, capability := range spec.Security.DropCapabilities {
		caps = append(caps, corev1.Capability(capability))
	}
	return caps
}
