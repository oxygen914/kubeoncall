package kubetooladapter

import (
	"context"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/fields"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/kubernetes"
)

type KubernetesReader struct {
	client kubernetes.Interface
	config Config
}

func NewKubernetesReader(client kubernetes.Interface, config Config) *KubernetesReader {
	return &KubernetesReader{client: client, config: config}
}

func (reader *KubernetesReader) Ready(ctx context.Context) error {
	_, err := reader.client.Discovery().ServerVersion()
	return err
}

func (reader *KubernetesReader) QueryEvents(ctx context.Context, parameters Parameters) (any, error) {
	namespace := text(parameters, "namespace")
	options := metav1.ListOptions{Limit: int64(reader.config.MaxEvents)}
	if uid := text(parameters, "resourceUid"); uid != "" {
		options.FieldSelector = fields.OneTermEqualSelector("involvedObject.uid", uid).String()
	}
	events, err := reader.client.CoreV1().Events(namespace).List(ctx, options)
	if err != nil {
		return nil, safeKubernetesError(err)
	}
	kind := text(parameters, "resourceKind", "resourceType")
	name := text(parameters, "resourceName", "target")
	start := parseTime(parameters, "startTime")
	end := parseTime(parameters, "endTime")
	items := make([]map[string]any, 0, len(events.Items))
	for _, event := range events.Items {
		observedAt := eventObservedAt(event)
		if kind != "" && !strings.EqualFold(event.InvolvedObject.Kind, kind) {
			continue
		}
		if name != "" && event.InvolvedObject.Name != name {
			continue
		}
		if start != nil && observedAt.Before(*start) {
			continue
		}
		if end != nil && observedAt.After(*end) {
			continue
		}
		items = append(items, map[string]any{
			"observedAt": observedAt.UTC().Format(time.RFC3339Nano),
			"summary":    event.Reason + ": " + bounded(event.Message, 1000),
			"message":    bounded(event.Message, 4096),
			"reason":     event.Reason,
			"eventType":  event.Type,
			"count":      event.Count,
			"resource": map[string]any{
				"kind": event.InvolvedObject.Kind,
				"name": event.InvolvedObject.Name,
				"uid":  string(event.InvolvedObject.UID),
			},
			"locator": map[string]any{
				"sequence": event.ResourceVersion,
			},
		})
	}
	sort.Slice(items, func(left, right int) bool {
		return fmt.Sprint(items[left]["observedAt"]) > fmt.Sprint(items[right]["observedAt"])
	})
	if len(items) > reader.config.MaxEvents {
		items = items[:reader.config.MaxEvents]
	}
	return map[string]any{"items": items}, nil
}

func (reader *KubernetesReader) QueryPodLogs(ctx context.Context, parameters Parameters) (any, error) {
	pods, err := reader.targetPods(ctx, parameters)
	if err != nil {
		return nil, err
	}
	previous := boolean(parameters["previous"])
	tailLines := boundedInt64(parameters["tailLines"], 200, 1, reader.config.MaxLogLines)
	containerFilter := text(parameters, "container")
	sinceTime := parseTime(parameters, "startTime")
	items := make([]map[string]any, 0)
	for _, pod := range pods {
		for _, container := range pod.Spec.Containers {
			if containerFilter != "" && container.Name != containerFilter {
				continue
			}
			options := &corev1.PodLogOptions{
				Container:  container.Name,
				Previous:   previous,
				TailLines:  &tailLines,
				Timestamps: true,
			}
			if sinceTime != nil {
				options.SinceTime = &metav1.Time{Time: *sinceTime}
			}
			stream, streamErr := reader.client.CoreV1().Pods(pod.Namespace).GetLogs(pod.Name, options).Stream(ctx)
			if streamErr != nil {
				if previous && previousLogUnavailable(streamErr) {
					continue
				}
				return nil, safeKubernetesError(streamErr)
			}
			raw, readErr := io.ReadAll(io.LimitReader(stream, reader.config.MaxLogBytes+1))
			_ = stream.Close()
			if readErr != nil {
				return nil, &APIError{Status: 502, Code: "LOG_READ_FAILED", Message: "Pod log stream could not be read"}
			}
			truncated := int64(len(raw)) > reader.config.MaxLogBytes
			if truncated {
				raw = raw[:reader.config.MaxLogBytes]
			}
			if len(strings.TrimSpace(string(raw))) == 0 {
				continue
			}
			items = append(items, map[string]any{
				"observedAt": time.Now().UTC().Format(time.RFC3339Nano),
				"summary":    fmt.Sprintf("%s logs for Pod/%s container %s", logMode(previous), pod.Name, container.Name),
				"snippet":    string(raw),
				"pod":        pod.Name,
				"container":  container.Name,
				"previous":   previous,
				"truncated":  truncated,
				"resource": map[string]any{
					"kind": "Pod",
					"name": pod.Name,
					"uid":  string(pod.UID),
				},
			})
		}
	}
	return map[string]any{"items": items}, nil
}

func (reader *KubernetesReader) DescribeResource(ctx context.Context, parameters Parameters) (any, error) {
	namespace := text(parameters, "namespace")
	kind := strings.ToLower(text(parameters, "resourceKind", "resourceType", "kind"))
	name := text(parameters, "resourceName", "target", "name")
	if name == "" {
		return nil, &APIError{Status: 400, Code: "RESOURCE_NAME_REQUIRED", Message: "resource name is required"}
	}
	switch kind {
	case "pod":
		pod, err := reader.client.CoreV1().Pods(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return podView(*pod), nil
	case "node":
		node, err := reader.client.CoreV1().Nodes().Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return nodeView(*node), nil
	case "deployment", "deploy":
		deployment, err := reader.client.AppsV1().Deployments(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return deploymentView(*deployment), nil
	case "statefulset", "sts":
		statefulSet, err := reader.client.AppsV1().StatefulSets(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return statefulSetView(*statefulSet), nil
	case "daemonset", "ds":
		daemonSet, err := reader.client.AppsV1().DaemonSets(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return daemonSetView(*daemonSet), nil
	case "":
		return reader.describeUnknownKind(ctx, namespace, name)
	default:
		return nil, &APIError{Status: 400, Code: "RESOURCE_KIND_UNSUPPORTED", Message: "resource kind is not supported"}
	}
}

func (reader *KubernetesReader) DescribeWorkload(ctx context.Context, parameters Parameters) (any, error) {
	namespace := text(parameters, "namespace")
	target := text(parameters, "target", "resourceName", "name")
	kind := strings.ToLower(text(parameters, "resourceKind", "resourceType", "kind"))
	if target == "" {
		return nil, &APIError{Status: 400, Code: "TARGET_REQUIRED", Message: "workload target is required"}
	}
	switch kind {
	case "deployment", "deploy":
		deployment, err := reader.client.AppsV1().Deployments(namespace).Get(ctx, target, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return deploymentView(*deployment), nil
	case "statefulset", "sts":
		statefulSet, err := reader.client.AppsV1().StatefulSets(namespace).Get(ctx, target, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return statefulSetView(*statefulSet), nil
	case "daemonset", "ds":
		daemonSet, err := reader.client.AppsV1().DaemonSets(namespace).Get(ctx, target, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return daemonSetView(*daemonSet), nil
	default:
		return reader.describeUnknownWorkload(ctx, namespace, target)
	}
}

func (reader *KubernetesReader) GetPods(ctx context.Context, parameters Parameters) (any, error) {
	pods, err := reader.targetPods(ctx, parameters)
	if err != nil {
		return nil, err
	}
	items := make([]map[string]any, 0, len(pods))
	for _, pod := range pods {
		items = append(items, podView(pod))
	}
	return map[string]any{"items": items}, nil
}

func (reader *KubernetesReader) targetPods(ctx context.Context, parameters Parameters) ([]corev1.Pod, error) {
	namespace := text(parameters, "namespace")
	kind := strings.ToLower(text(parameters, "resourceKind", "resourceType", "kind"))
	name := text(parameters, "resourceName", "target", "pod", "name")
	uid := text(parameters, "resourceUid")
	if kind == "pod" && name != "" {
		pod, err := reader.client.CoreV1().Pods(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		return []corev1.Pod{*pod}, nil
	}
	if uid != "" {
		pods, err := reader.client.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{Limit: int64(reader.config.MaxPods)})
		if err != nil {
			return nil, safeKubernetesError(err)
		}
		for _, pod := range pods.Items {
			if string(pod.UID) == uid {
				return []corev1.Pod{pod}, nil
			}
		}
		return []corev1.Pod{}, nil
	}
	selector, err := reader.workloadSelector(ctx, namespace, kind, name)
	if err != nil {
		if kind == "" && name != "" && isNotFoundAPIError(err) {
			pod, podErr := reader.client.CoreV1().Pods(namespace).Get(ctx, name, metav1.GetOptions{})
			if podErr == nil {
				return []corev1.Pod{*pod}, nil
			}
		}
		return nil, err
	}
	if selector == "" {
		return []corev1.Pod{}, nil
	}
	pods, listErr := reader.client.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: selector,
		Limit:         int64(reader.config.MaxPods),
	})
	if listErr != nil {
		return nil, safeKubernetesError(listErr)
	}
	return pods.Items, nil
}

func (reader *KubernetesReader) workloadSelector(ctx context.Context, namespace, kind, name string) (string, error) {
	if name == "" || name == "current-scope" {
		return "", nil
	}
	switch kind {
	case "deployment", "deploy":
		value, err := reader.client.AppsV1().Deployments(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return "", safeKubernetesError(err)
		}
		return metav1.FormatLabelSelector(value.Spec.Selector), nil
	case "statefulset", "sts":
		value, err := reader.client.AppsV1().StatefulSets(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return "", safeKubernetesError(err)
		}
		return metav1.FormatLabelSelector(value.Spec.Selector), nil
	case "daemonset", "ds":
		value, err := reader.client.AppsV1().DaemonSets(namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return "", safeKubernetesError(err)
		}
		return metav1.FormatLabelSelector(value.Spec.Selector), nil
	case "":
		for _, candidate := range []string{"deployment", "statefulset", "daemonset"} {
			selector, err := reader.workloadSelector(ctx, namespace, candidate, name)
			if err == nil {
				return selector, nil
			}
			if !isNotFoundAPIError(err) {
				return "", err
			}
		}
		return "", safeKubernetesError(apierrors.NewNotFound(schema.GroupResource{Group: "apps", Resource: "workloads"}, name))
	default:
		return "", &APIError{Status: 400, Code: "WORKLOAD_KIND_UNSUPPORTED", Message: "workload kind is not supported"}
	}
}

func (reader *KubernetesReader) describeUnknownKind(ctx context.Context, namespace, name string) (any, error) {
	pod, podErr := reader.client.CoreV1().Pods(namespace).Get(ctx, name, metav1.GetOptions{})
	if podErr == nil {
		return podView(*pod), nil
	}
	if !apierrors.IsNotFound(podErr) {
		return nil, safeKubernetesError(podErr)
	}
	return reader.describeUnknownWorkload(ctx, namespace, name)
}

func (reader *KubernetesReader) describeUnknownWorkload(ctx context.Context, namespace, name string) (any, error) {
	deployment, deploymentErr := reader.client.AppsV1().Deployments(namespace).Get(ctx, name, metav1.GetOptions{})
	if deploymentErr == nil {
		return deploymentView(*deployment), nil
	}
	if !apierrors.IsNotFound(deploymentErr) {
		return nil, safeKubernetesError(deploymentErr)
	}
	statefulSet, statefulSetErr := reader.client.AppsV1().StatefulSets(namespace).Get(ctx, name, metav1.GetOptions{})
	if statefulSetErr == nil {
		return statefulSetView(*statefulSet), nil
	}
	if !apierrors.IsNotFound(statefulSetErr) {
		return nil, safeKubernetesError(statefulSetErr)
	}
	daemonSet, daemonSetErr := reader.client.AppsV1().DaemonSets(namespace).Get(ctx, name, metav1.GetOptions{})
	if daemonSetErr == nil {
		return daemonSetView(*daemonSet), nil
	}
	return nil, safeKubernetesError(daemonSetErr)
}

func podView(pod corev1.Pod) map[string]any {
	containers := make([]map[string]any, 0, len(pod.Status.ContainerStatuses))
	for _, status := range pod.Status.ContainerStatuses {
		containers = append(containers, map[string]any{
			"name":         status.Name,
			"ready":        status.Ready,
			"restartCount": status.RestartCount,
			"state":        containerState(status.State),
			"lastState":    containerState(status.LastTerminationState),
		})
	}
	return map[string]any{
		"observedAt": time.Now().UTC().Format(time.RFC3339Nano),
		"summary":    fmt.Sprintf("Pod/%s is %s", pod.Name, pod.Status.Phase),
		"resource": map[string]any{
			"kind": "Pod",
			"name": pod.Name,
			"uid":  string(pod.UID),
		},
		"phase":             string(pod.Status.Phase),
		"podIP":             pod.Status.PodIP,
		"nodeName":          pod.Spec.NodeName,
		"containers":        containers,
		"creationTimestamp": pod.CreationTimestamp.UTC().Format(time.RFC3339Nano),
	}
}

func nodeView(node corev1.Node) map[string]any {
	conditions := make([]map[string]any, 0, len(node.Status.Conditions))
	ready := false
	for _, condition := range node.Status.Conditions {
		conditions = append(conditions, map[string]any{
			"type":               condition.Type,
			"status":             condition.Status,
			"reason":             condition.Reason,
			"message":            bounded(condition.Message, 1000),
			"lastTransitionTime": condition.LastTransitionTime.UTC().Format(time.RFC3339Nano),
		})
		if condition.Type == corev1.NodeReady && condition.Status == corev1.ConditionTrue {
			ready = true
		}
	}
	return map[string]any{
		"observedAt": time.Now().UTC().Format(time.RFC3339Nano),
		"summary":    fmt.Sprintf("Node/%s ready=%t", node.Name, ready),
		"resource": map[string]any{
			"kind": "Node",
			"name": node.Name,
			"uid":  string(node.UID),
		},
		"ready":       ready,
		"conditions":  conditions,
		"capacity":    node.Status.Capacity,
		"allocatable": node.Status.Allocatable,
	}
}

func deploymentView(deployment appsv1.Deployment) map[string]any {
	desired := int32Value(deployment.Spec.Replicas)
	ready := deployment.Status.ReadyReplicas
	return map[string]any{
		"observedAt":      time.Now().UTC().Format(time.RFC3339Nano),
		"summary":         fmt.Sprintf("Deployment/%s ready %d/%d", deployment.Name, ready, desired),
		"resource":        resource("Deployment", deployment.Name, string(deployment.UID)),
		"revision":        deployment.Annotations["deployment.kubernetes.io/revision"],
		"desiredReplicas": desired,
		"readyReplicas":   ready,
		"updatedReplicas": deployment.Status.UpdatedReplicas,
		"healthy":         desired == ready,
		"selector":        metav1.FormatLabelSelector(deployment.Spec.Selector),
		"conditions":      deploymentConditions(deployment.Status.Conditions),
	}
}

func statefulSetView(statefulSet appsv1.StatefulSet) map[string]any {
	desired := int32Value(statefulSet.Spec.Replicas)
	ready := statefulSet.Status.ReadyReplicas
	return map[string]any{
		"observedAt":      time.Now().UTC().Format(time.RFC3339Nano),
		"summary":         fmt.Sprintf("StatefulSet/%s ready %d/%d", statefulSet.Name, ready, desired),
		"resource":        resource("StatefulSet", statefulSet.Name, string(statefulSet.UID)),
		"revision":        statefulSet.Status.CurrentRevision,
		"desiredReplicas": desired,
		"readyReplicas":   ready,
		"updatedReplicas": statefulSet.Status.UpdatedReplicas,
		"healthy":         desired == ready,
		"selector":        metav1.FormatLabelSelector(statefulSet.Spec.Selector),
	}
}

func daemonSetView(daemonSet appsv1.DaemonSet) map[string]any {
	desired := daemonSet.Status.DesiredNumberScheduled
	ready := daemonSet.Status.NumberReady
	return map[string]any{
		"observedAt":      time.Now().UTC().Format(time.RFC3339Nano),
		"summary":         fmt.Sprintf("DaemonSet/%s ready %d/%d", daemonSet.Name, ready, desired),
		"resource":        resource("DaemonSet", daemonSet.Name, string(daemonSet.UID)),
		"revision":        daemonSet.Status.ObservedGeneration,
		"desiredReplicas": desired,
		"readyReplicas":   ready,
		"updatedReplicas": daemonSet.Status.UpdatedNumberScheduled,
		"healthy":         desired == ready,
		"selector":        metav1.FormatLabelSelector(daemonSet.Spec.Selector),
	}
}

func deploymentConditions(conditions []appsv1.DeploymentCondition) []map[string]any {
	result := make([]map[string]any, 0, len(conditions))
	for _, condition := range conditions {
		result = append(result, map[string]any{
			"type":               condition.Type,
			"status":             condition.Status,
			"reason":             condition.Reason,
			"message":            bounded(condition.Message, 1000),
			"lastTransitionTime": condition.LastTransitionTime.UTC().Format(time.RFC3339Nano),
		})
	}
	return result
}

func resource(kind, name, uid string) map[string]any {
	return map[string]any{"kind": kind, "name": name, "uid": uid}
}

func containerState(state corev1.ContainerState) map[string]any {
	if state.Running != nil {
		return map[string]any{"status": "RUNNING", "startedAt": state.Running.StartedAt.UTC().Format(time.RFC3339Nano)}
	}
	if state.Waiting != nil {
		return map[string]any{"status": "WAITING", "reason": state.Waiting.Reason, "message": bounded(state.Waiting.Message, 1000)}
	}
	if state.Terminated != nil {
		return map[string]any{
			"status":     "TERMINATED",
			"reason":     state.Terminated.Reason,
			"message":    bounded(state.Terminated.Message, 1000),
			"exitCode":   state.Terminated.ExitCode,
			"finishedAt": state.Terminated.FinishedAt.UTC().Format(time.RFC3339Nano),
		}
	}
	return map[string]any{"status": "UNKNOWN"}
}

func eventObservedAt(event corev1.Event) time.Time {
	if !event.EventTime.IsZero() {
		return event.EventTime.Time
	}
	if !event.LastTimestamp.IsZero() {
		return event.LastTimestamp.Time
	}
	if !event.FirstTimestamp.IsZero() {
		return event.FirstTimestamp.Time
	}
	return event.CreationTimestamp.Time
}

func safeKubernetesError(err error) error {
	if err == nil {
		return nil
	}
	switch {
	case apierrors.IsForbidden(err):
		return &APIError{Status: 403, Code: "RBAC_FORBIDDEN", Message: "Kubernetes RBAC denied the read operation"}
	case apierrors.IsNotFound(err):
		return &APIError{Status: 404, Code: "RESOURCE_NOT_FOUND", Message: "requested Kubernetes resource was not found"}
	case apierrors.IsBadRequest(err), apierrors.IsInvalid(err):
		return &APIError{Status: 400, Code: "INVALID_KUBERNETES_REQUEST", Message: "Kubernetes rejected the read request"}
	case apierrors.IsTimeout(err), apierrors.IsServerTimeout(err):
		return &APIError{Status: 504, Code: "KUBERNETES_TIMEOUT", Message: "Kubernetes read operation timed out"}
	default:
		return &APIError{Status: 502, Code: "KUBERNETES_UNAVAILABLE", Message: "Kubernetes API read operation failed"}
	}
}

func isNotFoundAPIError(err error) bool {
	apiError, ok := err.(*APIError)
	return ok && apiError.Status == 404
}

func previousLogUnavailable(err error) bool {
	return apierrors.IsBadRequest(err) || strings.Contains(strings.ToLower(err.Error()), "previous terminated container")
}

func boolean(value any) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		parsed, _ := strconv.ParseBool(typed)
		return parsed
	default:
		return false
	}
}

func boundedInt64(value any, fallback, minimum, maximum int64) int64 {
	var parsed int64
	switch typed := value.(type) {
	case float64:
		parsed = int64(typed)
	case int:
		parsed = int64(typed)
	case int64:
		parsed = typed
	case string:
		parsed, _ = strconv.ParseInt(typed, 10, 64)
	}
	if parsed < minimum {
		parsed = fallback
	}
	if parsed > maximum {
		parsed = maximum
	}
	return parsed
}

func int32Value(value *int32) int32 {
	if value == nil {
		return 0
	}
	return *value
}

func bounded(value string, limit int) string {
	if len(value) <= limit {
		return value
	}
	return value[:limit]
}

func logMode(previous bool) string {
	if previous {
		return "previous"
	}
	return "current"
}
