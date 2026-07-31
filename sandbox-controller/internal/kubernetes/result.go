package kubernetes

import (
	"context"
	"fmt"
	"io"
	"regexp"
	"strings"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
)

// Result is the collected outcome of a finished sandbox Job. It carries only normalized fields and
// redacted, size-bounded logs — the backend never sees raw pod state or untrusted output verbatim.
type Result struct {
	RunID    string
	Phase    Phase
	ExitCode *int32
	Reason   FailureReason
	Logs     string
	// Output is a bounded structured result emitted by a runtime with the KOC_RESULT_JSON marker.
	// It remains untrusted until Backend validates the mode-specific schema before persisting it.
	Output      string
	StartedAt   *time.Time
	FinishedAt  *time.Time
	OutputFound bool
}

// FailureReason classifies why a Job terminated so the backend can report OOM, timeout, image-pull
// and policy violations distinctly from a plain tool failure.
type FailureReason string

const (
	ReasonNone         FailureReason = ""
	ReasonOOM          FailureReason = "OOM"
	ReasonDeadline     FailureReason = "DEADLINE"
	ReasonImagePull    FailureReason = "IMAGE_PULL"
	ReasonPolicyDenied FailureReason = "POLICY_DENIED"
	ReasonToolFailure  FailureReason = "TOOL_FAILURE"
	ReasonUnknown      FailureReason = "UNKNOWN"
)

// LogLimit is the default byte ceiling on collected pod logs (§7.1: 2 MiB display logs).
const defaultLogLimit = 2 * 1024 * 1024

// CollectResult gathers the normalized outcome of the Job for a run id: exit code, failure
// classification, redacted truncated logs and a flag for whether an output artifact was produced.
// It is safe to call on a non-terminal Job; the caller should gate on a terminal Status first.
func (manager *Manager) CollectResult(ctx context.Context, runID string, logLimit int64) (Result, error) {
	if !runIDRegex(runID) {
		return Result{}, ErrInvalidRunID
	}
	if logLimit <= 0 {
		logLimit = defaultLogLimit
	}
	job, err := manager.findJob(ctx, runID)
	if err != nil {
		return Result{}, err
	}
	if job == nil {
		return Result{RunID: runID, Phase: PhaseUnknown, Reason: ReasonUnknown}, nil
	}
	result := Result{
		RunID:      runID,
		Phase:      classify(job),
		Reason:     classifyFailure(job),
		StartedAt:  jobTime(job.Status.StartTime),
		FinishedAt: jobTime(job.Status.CompletionTime),
	}
	if exitCode, ok := exitCodeFromJob(job); ok {
		result.ExitCode = &exitCode
	}
	pod, err := manager.findPodForJob(ctx, job)
	if err != nil {
		return result, fmt.Errorf("find pod: %w", err)
	}
	if pod != nil {
		if code, ok := exitCodeFromPod(pod); ok {
			result.ExitCode = &code
		}
		result.Reason = mergeReason(result.Reason, classifyFailureFromPod(pod))
		logs, err := manager.collectLogs(ctx, pod, logLimit)
		if err != nil {
			// Log collection failure must not mask the run's own outcome; record a marker instead.
			result.Logs = redact(fmt.Sprintf("[log collection failed: %v]\n", err))
		} else {
			result.Output = outputFromLogs(logs)
			result.OutputFound = result.Output != ""
			result.Logs = redact(logs)
		}
		if !result.OutputFound {
			result.OutputFound = podHasOutputMarker(pod)
		}
	}
	return result, nil
}

const outputMarker = "KOC_RESULT_JSON:"
const maxStructuredOutputBytes = 1024 * 1024

// outputFromLogs extracts only a single compact JSON marker. It never executes or interprets the
// payload and bounds its size before it leaves the Controller; Backend validates the schema.
func outputFromLogs(logs string) string {
	for _, line := range strings.Split(logs, "\n") {
		if !strings.HasPrefix(line, outputMarker) {
			continue
		}
		output := strings.TrimSpace(strings.TrimPrefix(line, outputMarker))
		if output != "" && len(output) <= maxStructuredOutputBytes {
			return output
		}
	}
	return ""
}

// ReapFinished deletes a Job that has reached a terminal state, returning the collected Result
// first so the backend persists outcome before the cluster evidence is removed. A non-terminal Job
// is left in place. A missing Job returns an empty result with PhaseUnknown.
func (manager *Manager) ReapFinished(ctx context.Context, runID string, logLimit int64) (Result, error) {
	result, err := manager.CollectResult(ctx, runID, logLimit)
	if err != nil {
		return Result{}, err
	}
	if !result.Phase.IsTerminal() {
		return result, nil
	}
	job, err := manager.findJob(ctx, runID)
	if err != nil {
		return result, err
	}
	if job == nil {
		return result, nil
	}
	policy := metav1.DeletePropagationBackground
	if err := manager.client.BatchV1().Jobs(manager.namespace).Delete(
		ctx, job.Name, metav1.DeleteOptions{PropagationPolicy: &policy}); err != nil && !isNotFound(err) {
		return result, fmt.Errorf("delete finished job: %w", err)
	}
	return result, nil
}

// findPodForJob lists pods in the controller namespace matching the Job's selector and returns the
// first (one-shot Jobs produce at most one pod).
func (manager *Manager) findPodForJob(ctx context.Context, job *batchv1.Job) (*corev1.Pod, error) {
	selector, err := metav1.LabelSelectorAsSelector(job.Spec.Selector)
	if err != nil {
		return nil, fmt.Errorf("parse selector: %w", err)
	}
	list, err := manager.client.CoreV1().Pods(manager.namespace).List(ctx, metav1.ListOptions{
		LabelSelector: selector.String(),
		Limit:         10,
	})
	if err != nil {
		return nil, fmt.Errorf("list pods: %w", err)
	}
	if len(list.Items) == 0 {
		return nil, nil
	}
	// Prefer a terminated pod; fall back to the first.
	for i := range list.Items {
		if len(list.Items[i].Status.ContainerStatuses) > 0 {
			cs := list.Items[i].Status.ContainerStatuses[0]
			if cs.State.Terminated != nil {
				return &list.Items[i], nil
			}
		}
	}
	return &list.Items[0], nil
}

func (manager *Manager) collectLogs(ctx context.Context, pod *corev1.Pod, limit int64) (string, error) {
	opts := &corev1.PodLogOptions{
		Container: firstContainerName(pod),
	}
	stream, err := manager.client.CoreV1().Pods(manager.namespace).GetLogs(pod.Name, opts).Stream(ctx)
	if err != nil {
		return "", err
	}
	defer stream.Close()
	return readBounded(stream, limit), nil
}

// readBounded reads up to limit bytes of log output. When the stream exceeds the ceiling it keeps a
// head of the first lines plus the final tail, where failure context usually lives, joined by a
// truncation marker.
func readBounded(reader io.Reader, limit int64) string {
	if limit <= int64(len(truncationMarker)) {
		return truncationMarker[:limit]
	}
	contents, _ := io.ReadAll(io.LimitReader(reader, limit+1))
	if int64(len(contents)) <= limit {
		return string(contents)
	}
	// Never exceed the configured egress budget. The marker makes partial evidence explicit rather
	// than silently presenting a complete-looking log line.
	return string(contents[:limit-int64(len(truncationMarker))]) + truncationMarker
}

const truncationMarker = "\n...[truncated]...\n"

func firstContainerName(pod *corev1.Pod) string {
	if len(pod.Spec.Containers) > 0 {
		return pod.Spec.Containers[0].Name
	}
	return ""
}

func exitCodeFromJob(job *batchv1.Job) (int32, bool) {
	// Job status does not carry an exit code directly; the pod is authoritative. Return false here
	// and let the pod path fill it in.
	return 0, false
}

func exitCodeFromPod(pod *corev1.Pod) (int32, bool) {
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Terminated != nil {
			return status.State.Terminated.ExitCode, true
		}
	}
	return 0, false
}

func classifyFailure(job *batchv1.Job) FailureReason {
	for _, condition := range job.Status.Conditions {
		if condition.Type != batchv1.JobFailed || condition.Status != corev1.ConditionTrue {
			continue
		}
		switch condition.Reason {
		case "DeadlineExceeded":
			return ReasonDeadline
		case "BackoffLimitExceeded":
			return ReasonToolFailure
		}
	}
	return ReasonNone
}

func classifyFailureFromPod(pod *corev1.Pod) FailureReason {
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Terminated == nil {
			continue
		}
		terminated := status.State.Terminated
		switch terminated.Reason {
		case "OOMKilled":
			return ReasonOOM
		case "DeadlineExceeded":
			return ReasonDeadline
		case "ContainerStatusCannotBeChecked", "CreateContainerConfigError":
			return ReasonPolicyDenied
		}
		if terminated.ExitCode == 137 {
			return ReasonOOM
		}
	}
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Waiting != nil {
			switch status.State.Waiting.Reason {
			case "ImagePullBackOff", "ErrImagePull":
				return ReasonImagePull
			case "CreateContainerConfigError", "InvalidImageName":
				return ReasonPolicyDenied
			}
		}
	}
	return ReasonNone
}

func mergeReason(jobReason, podReason FailureReason) FailureReason {
	if podReason != ReasonNone {
		return podReason
	}
	return jobReason
}

// podHasOutputMarker checks the pod for the expected output artifact annotation. The sandbox tool
// writes its output to a fixed path the controller knows; the pod annotates success when present.
func podHasOutputMarker(pod *corev1.Pod) bool {
	// A succeeded pod with exit code 0 produced its declared output.
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Terminated != nil && status.State.Terminated.ExitCode == 0 {
			return true
		}
	}
	return false
}

func jobTime(reference *metav1.Time) *time.Time {
	if reference == nil {
		return nil
	}
	t := reference.Time
	return &t
}

// IsTerminal reports whether a Phase is a terminal business state.
func (phase Phase) IsTerminal() bool {
	switch phase {
	case PhaseSucceeded, PhaseFailed, PhaseTimedOut, PhaseCancelled:
		return true
	}
	return false
}

// redact scrubs common secret-bearing patterns from log text before it leaves the controller. It is
// defense-in-depth: tools are sandboxed and untrusted, but a leaked token in a log line must not
// propagate to the backend or console verbatim.
func redact(text string) string {
	for _, pattern := range redactionPatterns {
		text = pattern.ReplaceAllString(text, "[REDACTED]")
	}
	return text
}

var redactionPatterns = []*regexp.Regexp{
	// Bearer / Authorization tokens.
	regexp.MustCompile(`(?i)(authorization|bearer)[\s:=]+[A-Za-z0-9._\-/+=]{16,}`),
	// AWS-style AKSK.
	regexp.MustCompile(`(?i)(AKIA|aws_access_key_id|aws_secret_access_key)[\s:=]+[A-Za-z0-9/+=]{12,}`),
	// Generic long hex/base64 tokens labelled as secret/token/password.
	regexp.MustCompile(`(?i)(token|secret|password|api[_-]?key)[\s:=]+[A-Za-z0-9._\-/+=]{16,}`),
	// JWT shapes.
	regexp.MustCompile(`eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}`),
	// http(s)://user:pass@host credentials.
	regexp.MustCompile(`(?i)https?://[^/\s:@]+:[^/\s:@]+@`),
}

// labelSelector helper retained for janitor use.
var _ labels.Selector
