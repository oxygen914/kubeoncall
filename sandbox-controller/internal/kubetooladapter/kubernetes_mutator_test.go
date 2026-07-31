package kubetooladapter

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"
)

func TestKubernetesMutatorScalesOnceAndReplaysDurableResult(t *testing.T) {
	client := fake.NewSimpleClientset(testDeployment())
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"scaleWorkload": {}}
	ledger := NewKubernetesOperationLedger(client, config.LedgerNamespace)
	mutator := NewKubernetesMutator(client, config, ledger)
	parameters := mutationParameters("scaleWorkload")
	parameters["replicas"] = 3

	first, err := mutator.Execute(context.Background(), "scaleWorkload", parameters)
	if err != nil {
		t.Fatalf("first mutation failed: %v", err)
	}
	second, err := mutator.Execute(context.Background(), "scaleWorkload", parameters)
	if err != nil {
		t.Fatalf("idempotent replay failed: %v", err)
	}
	deployment, _ := client.AppsV1().Deployments("kubeoncall-system").Get(
		context.Background(),
		"api",
		metav1.GetOptions{},
	)
	if deployment.Spec.Replicas == nil || *deployment.Spec.Replicas != 3 {
		t.Fatalf("expected replicas=3, got %v", deployment.Spec.Replicas)
	}
	if deployment.Annotations[operationHashAnnotation] !=
		shortOperationHash(text(parameters, "operationId")) {
		t.Fatalf("expected operation marker, got %v", deployment.Annotations)
	}
	if first.(map[string]any)["applied"] != true || second.(map[string]any)["replayed"] != true {
		t.Fatalf("unexpected mutation results: first=%v second=%v", first, second)
	}
}

func TestKubernetesMutatorSerializesConcurrentRequestsForTheSameOperation(t *testing.T) {
	client := fake.NewSimpleClientset(testDeployment())
	updateStarted := make(chan struct{})
	releaseUpdate := make(chan struct{})
	var updateCalls atomic.Int32
	client.PrependReactor("update", "deployments", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if updateCalls.Add(1) == 1 {
			close(updateStarted)
			<-releaseUpdate
		}
		return false, nil, nil
	})
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"scaleWorkload": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("scaleWorkload")
	parameters["replicas"] = 3
	type outcome struct {
		result any
		err    error
	}
	firstDone := make(chan outcome, 1)
	secondDone := make(chan outcome, 1)
	go func() {
		result, err := mutator.Execute(context.Background(), "scaleWorkload", parameters)
		firstDone <- outcome{result: result, err: err}
	}()
	<-updateStarted
	go func() {
		result, err := mutator.Execute(context.Background(), "scaleWorkload", parameters)
		secondDone <- outcome{result: result, err: err}
	}()
	close(releaseUpdate)

	first := <-firstDone
	second := <-secondDone
	if first.err != nil || second.err != nil {
		t.Fatalf("concurrent idempotent requests failed: first=%v second=%v", first.err, second.err)
	}
	if updateCalls.Load() != 1 {
		t.Fatalf("expected one Kubernetes update, got %d", updateCalls.Load())
	}
	if second.result.(map[string]any)["replayed"] != true {
		t.Fatalf("expected the second request to replay the durable result, got %v", second.result)
	}
}

func TestKubernetesMutatorRejectsOperationIdReuse(t *testing.T) {
	client := fake.NewSimpleClientset(testDeployment())
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"scaleWorkload": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("scaleWorkload")
	parameters["replicas"] = 3
	if _, err := mutator.Execute(context.Background(), "scaleWorkload", parameters); err != nil {
		t.Fatalf("first mutation failed: %v", err)
	}
	parameters["replicas"] = 4
	_, err := mutator.Execute(context.Background(), "scaleWorkload", parameters)
	assertAPIError(t, err, "OPERATION_ID_REUSED")
}

func TestKubernetesMutatorRecoversAfterLedgerCompletionFailure(t *testing.T) {
	client := fake.NewSimpleClientset(testDeployment())
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"rolloutRestart": {}}
	baseLedger := NewKubernetesOperationLedger(client, config.LedgerNamespace)
	ledger := &completeOnceFailingLedger{OperationLedger: baseLedger}
	mutator := NewKubernetesMutator(client, config, ledger)
	parameters := mutationParameters("rolloutRestart")

	_, firstErr := mutator.Execute(context.Background(), "rolloutRestart", parameters)
	assertAPIError(t, firstErr, "OPERATION_LEDGER_UNAVAILABLE")
	result, err := mutator.Execute(context.Background(), "rolloutRestart", parameters)
	if err != nil {
		t.Fatalf("retry after ledger failure failed: %v", err)
	}
	if result.(map[string]any)["reconciled"] != true {
		t.Fatalf("expected deterministic reconciliation, got %v", result)
	}
}

func TestKubernetesMutatorKeepsUnknownTimeoutOutcomeRecoverable(t *testing.T) {
	client := fake.NewSimpleClientset(testDeployment())
	updateCalls := 0
	client.PrependReactor("update", "deployments", func(action k8stesting.Action) (bool, runtime.Object, error) {
		updateCalls++
		if updateCalls == 1 {
			return true, nil, apierrors.NewTimeoutError("simulated unknown update outcome", 1)
		}
		return false, nil, nil
	})
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"scaleWorkload": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("scaleWorkload")
	parameters["replicas"] = 3

	_, firstErr := mutator.Execute(context.Background(), "scaleWorkload", parameters)
	assertAPIError(t, firstErr, "KUBERNETES_TIMEOUT")
	if _, err := mutator.Execute(context.Background(), "scaleWorkload", parameters); err != nil {
		t.Fatalf("retry after unknown timeout outcome failed: %v", err)
	}
}

func TestKubernetesMutatorPatchesOnlyAllowlistedPlainEnvironmentValue(t *testing.T) {
	deployment := testDeployment()
	deployment.Spec.Template.Spec.Containers[0].Env = []corev1.EnvVar{
		{Name: "REQUEST_TIMEOUT", Value: "30s"},
	}
	client := fake.NewSimpleClientset(deployment)
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"patchConfig": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("patchConfig")
	parameters["configKey"] = "REQUEST_TIMEOUT"
	parameters["desiredValue"] = "60s"

	if _, err := mutator.Execute(context.Background(), "patchConfig", parameters); err != nil {
		t.Fatalf("patchConfig failed: %v", err)
	}
	updated, _ := client.AppsV1().Deployments("kubeoncall-system").Get(
		context.Background(),
		"api",
		metav1.GetOptions{},
	)
	if updated.Spec.Template.Spec.Containers[0].Env[0].Value != "60s" {
		t.Fatalf("expected managed environment value to be updated: %v", updated.Spec.Template.Spec.Containers[0].Env)
	}
}

func TestKubernetesMutatorRestoresSelectedDeploymentRevision(t *testing.T) {
	deployment := testDeployment()
	deployment.Generation = 2
	deployment.Spec.Template.Spec.Containers[0].Image = "api:v2"
	initialOperationID := "exec-1:task-1:kubernetes.rolloutRestart"
	deployment.Annotations = map[string]string{
		operationHashAnnotation: shortOperationHash(initialOperationID),
	}
	deployment.Spec.Template.Annotations = map[string]string{
		operationHashAnnotation: shortOperationHash(initialOperationID),
	}
	controller := true
	oldReplicaSet := &appsv1.ReplicaSet{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "api-old",
			Namespace: "kubeoncall-system",
			UID:       types.UID("rs-1"),
			Labels:    map[string]string{"app": "api"},
			Annotations: map[string]string{
				deploymentRevisionAnnotation: "1",
			},
			OwnerReferences: []metav1.OwnerReference{{
				APIVersion: "apps/v1",
				Kind:       "Deployment",
				Name:       "api",
				UID:        deployment.UID,
				Controller: &controller,
			}},
		},
		Spec: appsv1.ReplicaSetSpec{
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "api"}},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": "api", podTemplateHashLabel: "old"}},
				Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "api", Image: "api:v1"}}},
			},
		},
	}
	client := fake.NewSimpleClientset(deployment, oldReplicaSet)
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"rolloutUndo": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("rolloutUndo")
	parameters["operationId"] = "exec-1:task-1:rollback:rolloutUndo"
	parameters["expectedGeneration"] = 1
	parameters["revision"] = "1"
	parameters["rollback"] = true
	parameters["rollbackOfOperationId"] = initialOperationID

	if _, err := mutator.Execute(context.Background(), "rolloutUndo", parameters); err != nil {
		t.Fatalf("rolloutUndo failed: %v", err)
	}
	updated, _ := client.AppsV1().Deployments("kubeoncall-system").Get(
		context.Background(),
		"api",
		metav1.GetOptions{},
	)
	if updated.Spec.Template.Spec.Containers[0].Image != "api:v1" {
		t.Fatalf("expected old revision image, got %s", updated.Spec.Template.Spec.Containers[0].Image)
	}
	if _, exists := updated.Spec.Template.Labels[podTemplateHashLabel]; exists {
		t.Fatal("pod-template-hash must not be copied into the restored Deployment template")
	}
	if updated.Annotations[operationHashAnnotation] !=
		shortOperationHash(text(parameters, "operationId")) {
		t.Fatalf("expected rollback operation marker, got %v", updated.Annotations)
	}
}

func TestKubernetesMutatorRequiresTheOriginalOperationForRollback(t *testing.T) {
	client := fake.NewSimpleClientset(testDeployment())
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"scaleWorkload": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("scaleWorkload")
	parameters["operationId"] = "exec-1:task-1:rollback:scaleWorkload"
	parameters["rollback"] = true
	parameters["replicas"] = 1
	parameters["expectedCurrentReplicas"] = 2

	_, err := mutator.Execute(context.Background(), "scaleWorkload", parameters)

	assertAPIError(t, err, "MUTATION_GUARD_REQUIRED")
}

func TestKubernetesMutatorRejectsRollbackAfterAnotherOperationChangedTheTarget(t *testing.T) {
	deployment := testDeployment()
	replicas := int32(3)
	deployment.Spec.Replicas = &replicas
	deployment.Annotations = map[string]string{
		operationHashAnnotation: shortOperationHash("exec-2:task-2:kubernetes.scaleWorkload"),
	}
	client := fake.NewSimpleClientset(deployment)
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"scaleWorkload": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("scaleWorkload")
	parameters["operationId"] = "exec-1:task-1:rollback:scaleWorkload"
	parameters["rollbackOfOperationId"] = "exec-1:task-1:kubernetes.scaleWorkload"
	parameters["rollback"] = true
	parameters["replicas"] = 2
	parameters["expectedCurrentReplicas"] = 3

	_, err := mutator.Execute(context.Background(), "scaleWorkload", parameters)

	assertAPIError(t, err, "TARGET_DRIFT")
}

func TestKubernetesMutatorPersistsTargetDriftFailure(t *testing.T) {
	client := fake.NewSimpleClientset(testDeployment())
	config := testMutationConfig()
	config.AllowedActions = map[string]struct{}{"scaleWorkload": {}}
	mutator := NewKubernetesMutator(
		client,
		config,
		NewKubernetesOperationLedger(client, config.LedgerNamespace),
	)
	parameters := mutationParameters("scaleWorkload")
	parameters["expectedGeneration"] = 9
	parameters["replicas"] = 3

	_, firstErr := mutator.Execute(context.Background(), "scaleWorkload", parameters)
	assertAPIError(t, firstErr, "TARGET_DRIFT")
	_, replayErr := mutator.Execute(context.Background(), "scaleWorkload", parameters)
	assertAPIError(t, replayErr, "TARGET_DRIFT")
}

func testDeployment() *appsv1.Deployment {
	replicas := int32(2)
	return &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{
			Name:            "api",
			Namespace:       "kubeoncall-system",
			UID:             types.UID("uid-api"),
			ResourceVersion: "1",
			Generation:      1,
		},
		Spec: appsv1.DeploymentSpec{
			Replicas: &replicas,
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "api"}},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": "api"}},
				Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "api", Image: "api:v2"}}},
			},
		},
	}
}

func mutationParameters(action string) Parameters {
	return Parameters{
		"cluster":             "local",
		"namespace":           "kubeoncall-system",
		"target":              "api",
		"resourceKind":        "Deployment",
		"operationId":         "exec-1:task-1:kubernetes." + action,
		"expectedResourceUid": "uid-api",
		"expectedGeneration":  1,
	}
}

func assertAPIError(t *testing.T, err error, code string) {
	t.Helper()
	var apiError *APIError
	if !errors.As(err, &apiError) {
		t.Fatalf("expected APIError %s, got %v", code, err)
	}
	if apiError.Code != code {
		t.Fatalf("expected APIError %s, got %s (%v)", code, apiError.Code, apiError)
	}
}

type completeOnceFailingLedger struct {
	OperationLedger
	failed bool
}

func (ledger *completeOnceFailingLedger) Complete(
	ctx context.Context,
	record OperationRecord,
	result map[string]any,
) error {
	if !ledger.failed {
		ledger.failed = true
		return errors.New("simulated ledger outage")
	}
	return ledger.OperationLedger.Complete(ctx, record, result)
}
