package kubetooladapter

import (
	"context"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/fake"
)

func TestKubernetesReaderReturnsScopedEventsAndResourceState(t *testing.T) {
	now := metav1.NewTime(time.Now().UTC())
	replicas := int32(1)
	client := fake.NewSimpleClientset(
		&corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{
				Name:      "adapter-abc",
				Namespace: "kubeoncall-system",
				UID:       types.UID("pod-uid"),
				Labels:    map[string]string{"app": "adapter"},
			},
			Spec: corev1.PodSpec{Containers: []corev1.Container{{Name: "adapter"}}},
			Status: corev1.PodStatus{
				Phase: corev1.PodRunning,
				ContainerStatuses: []corev1.ContainerStatus{{
					Name:  "adapter",
					Ready: true,
				}},
			},
		},
		&appsv1.Deployment{
			ObjectMeta: metav1.ObjectMeta{
				Name:        "adapter",
				Namespace:   "kubeoncall-system",
				UID:         types.UID("deployment-uid"),
				Annotations: map[string]string{"deployment.kubernetes.io/revision": "3"},
			},
			Spec: appsv1.DeploymentSpec{
				Replicas: &replicas,
				Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "adapter"}},
			},
			Status: appsv1.DeploymentStatus{ReadyReplicas: 1, UpdatedReplicas: 1},
		},
		&corev1.Event{
			ObjectMeta: metav1.ObjectMeta{
				Name:              "adapter-created",
				Namespace:         "kubeoncall-system",
				CreationTimestamp: now,
			},
			InvolvedObject: corev1.ObjectReference{
				Kind:      "Pod",
				Name:      "adapter-abc",
				Namespace: "kubeoncall-system",
				UID:       types.UID("pod-uid"),
			},
			Reason:  "Started",
			Message: "Started container adapter",
			Type:    corev1.EventTypeNormal,
			Count:   1,
		},
	)
	reader := NewKubernetesReader(client, testConfig())

	rawEvents, err := reader.QueryEvents(context.Background(), Parameters{
		"namespace":    "kubeoncall-system",
		"resourceKind": "Pod",
		"resourceName": "adapter-abc",
		"resourceUid":  "pod-uid",
	})
	if err != nil {
		t.Fatalf("query events: %v", err)
	}
	events := rawEvents.(map[string]any)["items"].([]map[string]any)
	if len(events) != 1 || events[0]["reason"] != "Started" {
		t.Fatalf("unexpected events: %v", events)
	}

	resourceState, err := reader.DescribeResource(context.Background(), Parameters{
		"namespace":    "kubeoncall-system",
		"resourceKind": "Pod",
		"resourceName": "adapter-abc",
	})
	if err != nil {
		t.Fatalf("describe resource: %v", err)
	}
	if resourceState.(map[string]any)["phase"] != string(corev1.PodRunning) {
		t.Fatalf("unexpected resource state: %v", resourceState)
	}

	workload, err := reader.DescribeWorkload(context.Background(), Parameters{
		"namespace":    "kubeoncall-system",
		"resourceKind": "Deployment",
		"target":       "adapter",
	})
	if err != nil {
		t.Fatalf("describe workload: %v", err)
	}
	if workload.(map[string]any)["healthy"] != true {
		t.Fatalf("unexpected workload state: %v", workload)
	}

	pods, err := reader.GetPods(context.Background(), Parameters{
		"namespace":    "kubeoncall-system",
		"resourceKind": "Deployment",
		"target":       "adapter",
	})
	if err != nil {
		t.Fatalf("get pods: %v", err)
	}
	if len(pods.(map[string]any)["items"].([]map[string]any)) != 1 {
		t.Fatalf("unexpected pods: %v", pods)
	}
}
