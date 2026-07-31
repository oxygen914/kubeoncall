package kubetooladapter

import (
	"context"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	coordinationv1 "k8s.io/api/coordination/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	k8sresource "k8s.io/apimachinery/pkg/api/resource"
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
			Spec: corev1.PodSpec{NodeName: "worker-1", Containers: []corev1.Container{{Name: "adapter"}}},
			Status: corev1.PodStatus{
				Phase: corev1.PodRunning,
				ContainerStatuses: []corev1.ContainerStatus{{
					Name:  "adapter",
					Ready: true,
				}},
			},
		},
		&corev1.Node{
			ObjectMeta: metav1.ObjectMeta{Name: "worker-1", UID: types.UID("node-uid")},
			Status: corev1.NodeStatus{Conditions: []corev1.NodeCondition{
				{Type: corev1.NodeReady, Status: corev1.ConditionTrue},
				{Type: corev1.NodeMemoryPressure, Status: corev1.ConditionTrue},
			}},
		},
		&appsv1.Deployment{
			ObjectMeta: metav1.ObjectMeta{
				Name:            "adapter",
				Namespace:       "kubeoncall-system",
				UID:             types.UID("deployment-uid"),
				ResourceVersion: "9",
				Generation:      4,
				Annotations: map[string]string{
					"deployment.kubernetes.io/revision": "3",
					operationHashAnnotation:             "operation-marker",
				},
			},
			Spec: appsv1.DeploymentSpec{
				Replicas: &replicas,
				Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "adapter"}},
				Template: corev1.PodTemplateSpec{
					Spec: corev1.PodSpec{Containers: []corev1.Container{{
						Name: "adapter",
						Env:  []corev1.EnvVar{{Name: "REQUEST_TIMEOUT", Value: "30s"}},
					}}},
				},
			},
			Status: appsv1.DeploymentStatus{ReadyReplicas: 1, UpdatedReplicas: 1, ObservedGeneration: 4},
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
	config := testConfig()
	config.AllowedConfigKeys = map[string]struct{}{"REQUEST_TIMEOUT": {}}
	reader := NewKubernetesReader(client, config)

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
	nodeContext := resourceState.(map[string]any)["nodeContext"].(map[string]any)
	if nodeContext["collectionStatus"] != "SUCCEEDED" || nodeContext["memoryPressure"] != true {
		t.Fatalf("unexpected pod node context: %v", nodeContext)
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
	workloadView := workload.(map[string]any)
	if workloadView["generation"] != int64(4) || workloadView["resourceVersion"] != "9" {
		t.Fatalf("missing workload mutation guard fields: %v", workloadView)
	}
	if workloadView["operationMarker"] != "operation-marker" {
		t.Fatalf("missing workload operation marker: %v", workloadView)
	}
	configuration := workloadView["configuration"].(map[string]any)
	if configuration["REQUEST_TIMEOUT"] != "30s" {
		t.Fatalf("unexpected managed configuration snapshot: %v", configuration)
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

func TestKubernetesReaderReturnsBoundedNodeImpactLeaseOwnersPDBAndCapacity(t *testing.T) {
	controller := true
	renewTime := metav1.NewMicroTime(time.Now().UTC().Add(-30 * time.Second))
	client := fake.NewSimpleClientset(
		&corev1.Node{
			ObjectMeta: metav1.ObjectMeta{Name: "worker-1", UID: types.UID("node-uid")},
			Status: corev1.NodeStatus{
				Conditions: []corev1.NodeCondition{{
					Type:               corev1.NodeReady,
					Status:             corev1.ConditionFalse,
					Reason:             "KubeletNotReady",
					LastTransitionTime: metav1.Now(),
				}},
				Capacity: corev1.ResourceList{
					corev1.ResourceCPU:    k8sresource.MustParse("4"),
					corev1.ResourceMemory: k8sresource.MustParse("8Gi"),
					corev1.ResourcePods:   k8sresource.MustParse("110"),
				},
				Allocatable: corev1.ResourceList{
					corev1.ResourceCPU:    k8sresource.MustParse("3900m"),
					corev1.ResourceMemory: k8sresource.MustParse("7Gi"),
					corev1.ResourcePods:   k8sresource.MustParse("100"),
				},
			},
		},
		&coordinationv1.Lease{
			ObjectMeta: metav1.ObjectMeta{Name: "worker-1", Namespace: corev1.NamespaceNodeLease},
			Spec: coordinationv1.LeaseSpec{
				HolderIdentity:       stringPointer("worker-1"),
				RenewTime:            &renewTime,
				LeaseDurationSeconds: int32Pointer(40),
			},
		},
		&corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{
				Name:      "payment-api-1",
				Namespace: "kubeoncall-system",
				UID:       types.UID("pod-uid"),
				Labels:    map[string]string{"app": "payment-api"},
				OwnerReferences: []metav1.OwnerReference{{
					Kind:       "ReplicaSet",
					Name:       "payment-api-rs",
					UID:        types.UID("rs-uid"),
					Controller: &controller,
				}},
			},
			Spec: corev1.PodSpec{
				NodeName: "worker-1",
				Containers: []corev1.Container{{
					Name: "app",
					Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
						corev1.ResourceCPU:    k8sresource.MustParse("500m"),
						corev1.ResourceMemory: k8sresource.MustParse("256Mi"),
					}},
				}},
			},
			Status: corev1.PodStatus{Phase: corev1.PodRunning},
		},
		&policyv1.PodDisruptionBudget{
			ObjectMeta: metav1.ObjectMeta{Name: "payment-api", Namespace: "kubeoncall-system"},
			Spec: policyv1.PodDisruptionBudgetSpec{
				Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "payment-api"}},
			},
			Status: policyv1.PodDisruptionBudgetStatus{
				DisruptionsAllowed: 0,
				CurrentHealthy:     1,
				DesiredHealthy:     1,
				ExpectedPods:       1,
			},
		},
	)
	reader := NewKubernetesReader(client, testConfig())

	raw, err := reader.DescribeResource(context.Background(), Parameters{
		"namespace":    "kubeoncall-system",
		"resourceKind": "Node",
		"resourceName": "worker-1",
	})
	if err != nil {
		t.Fatalf("describe node: %v", err)
	}

	node := raw.(map[string]any)
	if node["ready"] != false {
		t.Fatalf("expected NotReady node: %v", node)
	}
	lease := node["lease"].(map[string]any)
	if lease["collectionStatus"] != "SUCCEEDED" || lease["holderIdentity"] != "worker-1" {
		t.Fatalf("unexpected lease: %v", lease)
	}
	pods := node["affectedPods"].([]map[string]any)
	if len(pods) != 1 {
		t.Fatalf("unexpected affected pods: %v", pods)
	}
	owners := pods[0]["owners"].([]map[string]any)
	if len(owners) != 1 || owners[0]["name"] != "payment-api-rs" {
		t.Fatalf("unexpected owners: %v", owners)
	}
	budgets := pods[0]["podDisruptionBudgets"].([]map[string]any)
	if len(budgets) != 1 || budgets[0]["disruptionsAllowed"] != int32(0) {
		t.Fatalf("unexpected PDBs: %v", budgets)
	}
	allocated := node["allocatedRequestsWithinScope"].(map[string]any)
	if allocated["cpuMillis"] != int64(500) || allocated["memoryBytes"] != int64(256*1024*1024) {
		t.Fatalf("unexpected allocated resources: %v", allocated)
	}
	remaining := node["remainingAllocatableWithinScope"].(map[string]any)
	if remaining["cpuMillis"] != int64(3400) {
		t.Fatalf("unexpected remaining capacity: %v", remaining)
	}
	scope := node["impactScope"].(map[string]any)
	if scope["coverage"] != "ALLOWED_NAMESPACES" || scope["complete"] != false {
		t.Fatalf("unexpected impact scope: %v", scope)
	}
}

func stringPointer(value string) *string {
	return &value
}

func int32Pointer(value int32) *int32 {
	return &value
}
