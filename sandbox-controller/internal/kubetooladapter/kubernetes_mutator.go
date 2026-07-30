package kubetooladapter

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	apiequality "k8s.io/apimachinery/pkg/api/equality"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/validation"
	"k8s.io/client-go/kubernetes"
)

const (
	restartAtAnnotation          = "kubectl.kubernetes.io/restartedAt"
	operationHashAnnotation      = "ops.kubeoncall.io/operation-id"
	deploymentRevisionAnnotation = "deployment.kubernetes.io/revision"
	podTemplateHashLabel         = "pod-template-hash"
)

var operationIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{15,255}$`)

type Mutator interface {
	Ready(context.Context) error
	Execute(context.Context, string, Parameters) (any, error)
}

type KubernetesMutator struct {
	client         kubernetes.Interface
	config         MutationConfig
	ledger         OperationLedger
	now            func() time.Time
	operationLocks [64]sync.Mutex
}

func NewKubernetesMutator(
	client kubernetes.Interface,
	config MutationConfig,
	ledger OperationLedger,
) *KubernetesMutator {
	return &KubernetesMutator{
		client: client,
		config: config,
		ledger: ledger,
		now:    func() time.Time { return time.Now().UTC() },
	}
}

func (mutator *KubernetesMutator) Ready(ctx context.Context) error {
	if _, err := mutator.client.Discovery().ServerVersion(); err != nil {
		return err
	}
	return mutator.ledger.Ready(ctx)
}

func (mutator *KubernetesMutator) Execute(
	ctx context.Context,
	action string,
	parameters Parameters,
) (any, error) {
	if err := mutator.validate(action, parameters); err != nil {
		return nil, err
	}
	operationID := text(parameters, "operationId")
	operationLock := mutator.operationLock(operationID)
	operationLock.Lock()
	defer operationLock.Unlock()

	requestHash, err := mutationRequestHash(action, parameters)
	if err != nil {
		return nil, &APIError{Status: 400, Code: "INVALID_PAYLOAD", Message: "mutation request could not be normalized"}
	}
	record := OperationRecord{
		OperationID: operationID,
		RequestHash: requestHash,
		Action:      action,
		Status:      "PENDING",
		StartedAt:   mutator.now(),
	}
	stored, created, err := mutator.ledger.Begin(ctx, record)
	if err != nil {
		return nil, ledgerAPIError(err)
	}
	if !created {
		if stored.RequestHash != requestHash || stored.Action != action {
			return nil, &APIError{
				Status:  http.StatusConflict,
				Code:    "OPERATION_ID_REUSED",
				Message: "operationId was already used for a different mutation request",
			}
		}
		switch stored.Status {
		case "SUCCEEDED":
			return replayResult(stored.Result), nil
		case "FAILED":
			return nil, &APIError{
				Status:  nonZero(stored.ErrorStatus, http.StatusConflict),
				Code:    nonBlank(stored.ErrorCode, "OPERATION_PREVIOUSLY_FAILED"),
				Message: nonBlank(stored.ErrorMessage, "operation previously failed"),
			}
		case "PENDING":
			record = stored
		default:
			return nil, &APIError{
				Status:  http.StatusConflict,
				Code:    "OPERATION_LEDGER_STATE_INVALID",
				Message: "operation ledger contains an unsupported state",
			}
		}
	}

	result, executeErr := mutator.executeDeployment(ctx, action, parameters, record)
	if executeErr != nil {
		apiError := mutationAPIError(executeErr)
		// A timeout or transport failure can happen after the API server accepted a deterministic
		// update. Keep the ledger pending so the same operationId can reconcile the desired state
		// instead of permanently recording an unknown outcome as failed.
		if apiError.Status >= http.StatusInternalServerError {
			return nil, apiError
		}
		if err := mutator.ledger.Fail(ctx, record, apiError); err != nil {
			return nil, ledgerAPIError(err)
		}
		return nil, apiError
	}
	resultMap := copyAnyMap(result)
	resultMap["operationId"] = operationID
	resultMap["action"] = action
	if err := mutator.ledger.Complete(ctx, record, resultMap); err != nil {
		return nil, ledgerAPIError(err)
	}
	return resultMap, nil
}

func (mutator *KubernetesMutator) operationLock(operationID string) *sync.Mutex {
	digest := sha256.Sum256([]byte(operationID))
	return &mutator.operationLocks[int(digest[0])%len(mutator.operationLocks)]
}

func (mutator *KubernetesMutator) validate(action string, parameters Parameters) error {
	if !mutator.config.actionAllowed(action) {
		return &APIError{
			Status:  http.StatusForbidden,
			Code:    "ACTION_FORBIDDEN",
			Message: "requested mutation action is outside the adapter allowlist",
		}
	}
	operationID := text(parameters, "operationId")
	if !operationIDPattern.MatchString(operationID) {
		return &APIError{
			Status:  http.StatusBadRequest,
			Code:    "OPERATION_ID_INVALID",
			Message: "operationId must contain 16 to 256 safe characters",
		}
	}
	namespace := text(parameters, "namespace")
	target := text(parameters, "target", "resourceName", "name")
	if namespace == "" || target == "" {
		return &APIError{
			Status:  http.StatusBadRequest,
			Code:    "TARGET_REQUIRED",
			Message: "namespace and workload target are required",
		}
	}
	if !mutator.config.namespaceAllowed(namespace) {
		return &APIError{
			Status:  http.StatusForbidden,
			Code:    "NAMESPACE_FORBIDDEN",
			Message: "requested namespace is outside the mutation allowlist",
		}
	}
	if len(validation.IsDNS1123Label(namespace)) > 0 || len(validation.IsDNS1123Subdomain(target)) > 0 {
		return &APIError{
			Status:  http.StatusBadRequest,
			Code:    "TARGET_INVALID",
			Message: "namespace or workload target is invalid",
		}
	}
	kind := strings.ToLower(text(parameters, "resourceKind", "resourceType", "kind"))
	if kind != "" && kind != "deployment" && kind != "deploy" {
		return &APIError{
			Status:  http.StatusBadRequest,
			Code:    "WORKLOAD_KIND_UNSUPPORTED",
			Message: "governed mutations currently support Deployment targets only",
		}
	}
	if text(parameters, "expectedResourceUid") == "" || integer(parameters["expectedGeneration"]) < 1 {
		return &APIError{
			Status:  http.StatusConflict,
			Code:    "MUTATION_GUARD_REQUIRED",
			Message: "verified workload UID and generation are required",
		}
	}
	if boolean(parameters["rollback"]) &&
		!operationIDPattern.MatchString(text(parameters, "rollbackOfOperationId")) {
		return &APIError{
			Status:  http.StatusConflict,
			Code:    "MUTATION_GUARD_REQUIRED",
			Message: "rollbackOfOperationId is required for compensating mutations",
		}
	}
	switch action {
	case "scaleWorkload":
		replicas, ok := exactInteger(parameters["replicas"])
		if !ok || replicas < 0 || replicas > int64(mutator.config.MaxReplicas) {
			return &APIError{
				Status:  http.StatusBadRequest,
				Code:    "REPLICA_COUNT_INVALID",
				Message: "replica count is outside the configured limit",
			}
		}
	case "rolloutUndo":
		revision := text(parameters, "revision")
		if revision == "" {
			return &APIError{Status: 400, Code: "REVISION_REQUIRED", Message: "deployment revision is required"}
		}
		if _, err := strconv.ParseInt(revision, 10, 64); err != nil {
			return &APIError{Status: 400, Code: "REVISION_INVALID", Message: "deployment revision must be numeric"}
		}
	case "patchConfig":
		key := text(parameters, "configKey")
		if !mutator.config.configKeyAllowed(key) {
			return &APIError{
				Status:  http.StatusForbidden,
				Code:    "CONFIG_KEY_FORBIDDEN",
				Message: "config key is outside the mutation allowlist",
			}
		}
		value, exists := parameters["desiredValue"]
		if !exists || value == nil || len([]byte(fmt.Sprint(value))) > mutator.config.MaxConfigValueBytes {
			return &APIError{
				Status:  http.StatusBadRequest,
				Code:    "CONFIG_VALUE_INVALID",
				Message: "config value is missing or exceeds the configured limit",
			}
		}
	}
	return nil
}

func (mutator *KubernetesMutator) executeDeployment(
	ctx context.Context,
	action string,
	parameters Parameters,
	record OperationRecord,
) (map[string]any, error) {
	namespace := text(parameters, "namespace")
	target := text(parameters, "target", "resourceName", "name")
	deployment, err := mutator.client.AppsV1().Deployments(namespace).Get(ctx, target, metav1.GetOptions{})
	if err != nil {
		return nil, err
	}
	if string(deployment.UID) != text(parameters, "expectedResourceUid") {
		return nil, targetDrift("workload UID changed after verification")
	}
	if err := verifyRollbackOperationGuard(deployment, parameters, record); err != nil {
		return nil, err
	}

	switch action {
	case "scaleWorkload":
		return mutator.scaleDeployment(ctx, deployment, parameters, record)
	case "rolloutRestart":
		return mutator.restartDeployment(ctx, deployment, parameters, record)
	case "rolloutUndo":
		return mutator.undoDeployment(ctx, deployment, parameters, record)
	case "patchConfig":
		return mutator.patchDeploymentConfig(ctx, deployment, parameters, record)
	default:
		return nil, &APIError{Status: 400, Code: "UNSUPPORTED_ACTION", Message: "mutation action is unsupported"}
	}
}

func (mutator *KubernetesMutator) scaleDeployment(
	ctx context.Context,
	deployment *appsv1.Deployment,
	parameters Parameters,
	record OperationRecord,
) (map[string]any, error) {
	replicas, _ := exactInteger(parameters["replicas"])
	operationHash := shortOperationHash(record.OperationID)
	if int64(int32Value(deployment.Spec.Replicas)) == replicas &&
		deployment.Annotations[operationHashAnnotation] == operationHash {
		return mutationResult(deployment, false, true), nil
	}
	if err := verifyRollbackReplicaGuard(deployment, parameters); err != nil {
		return nil, err
	}
	if err := verifyGeneration(deployment, parameters); err != nil {
		return nil, err
	}
	next := deployment.DeepCopy()
	value := int32(replicas)
	next.Spec.Replicas = &value
	markDeployment(next, operationHash)
	updated, err := mutator.client.AppsV1().Deployments(next.Namespace).Update(ctx, next, metav1.UpdateOptions{})
	if err != nil {
		return nil, err
	}
	return mutationResult(updated, true, false), nil
}

func (mutator *KubernetesMutator) restartDeployment(
	ctx context.Context,
	deployment *appsv1.Deployment,
	parameters Parameters,
	record OperationRecord,
) (map[string]any, error) {
	operationHash := shortOperationHash(record.OperationID)
	if deployment.Spec.Template.Annotations[operationHashAnnotation] == operationHash &&
		deployment.Annotations[operationHashAnnotation] == operationHash {
		return mutationResult(deployment, false, true), nil
	}
	if err := verifyGeneration(deployment, parameters); err != nil {
		return nil, err
	}
	next := deployment.DeepCopy()
	if next.Spec.Template.Annotations == nil {
		next.Spec.Template.Annotations = make(map[string]string)
	}
	markDeployment(next, operationHash)
	next.Spec.Template.Annotations[restartAtAnnotation] = record.StartedAt.UTC().Format(time.RFC3339Nano)
	next.Spec.Template.Annotations[operationHashAnnotation] = operationHash
	updated, err := mutator.client.AppsV1().Deployments(next.Namespace).Update(ctx, next, metav1.UpdateOptions{})
	if err != nil {
		return nil, err
	}
	return mutationResult(updated, true, false), nil
}

func (mutator *KubernetesMutator) undoDeployment(
	ctx context.Context,
	deployment *appsv1.Deployment,
	parameters Parameters,
	record OperationRecord,
) (map[string]any, error) {
	template, err := mutator.revisionTemplate(ctx, deployment, text(parameters, "revision"))
	if err != nil {
		return nil, err
	}
	operationHash := shortOperationHash(record.OperationID)
	if apiequality.Semantic.DeepEqual(deployment.Spec.Template, template) &&
		deployment.Annotations[operationHashAnnotation] == operationHash {
		return mutationResult(deployment, false, true), nil
	}
	if apiequality.Semantic.DeepEqual(deployment.Spec.Template, template) {
		if expectedOperationID := text(parameters, "rollbackOfOperationId"); expectedOperationID != "" &&
			deployment.Spec.Template.Annotations[operationHashAnnotation] != shortOperationHash(expectedOperationID) {
			return nil, targetDrift("workload no longer matches the operation selected for rollback")
		}
		if err := verifyGeneration(deployment, parameters); err != nil {
			return nil, err
		}
		next := deployment.DeepCopy()
		markDeployment(next, operationHash)
		updated, updateErr := mutator.client.AppsV1().Deployments(next.Namespace).Update(
			ctx,
			next,
			metav1.UpdateOptions{},
		)
		if updateErr != nil {
			return nil, updateErr
		}
		return mutationResult(updated, true, false), nil
	}
	if expectedOperationID := text(parameters, "rollbackOfOperationId"); expectedOperationID != "" {
		if deployment.Spec.Template.Annotations[operationHashAnnotation] != shortOperationHash(expectedOperationID) {
			return nil, targetDrift("workload no longer matches the operation selected for rollback")
		}
	} else if err := verifyGeneration(deployment, parameters); err != nil {
		return nil, err
	}
	next := deployment.DeepCopy()
	next.Spec.Template = *template.DeepCopy()
	markDeployment(next, operationHash)
	updated, err := mutator.client.AppsV1().Deployments(next.Namespace).Update(ctx, next, metav1.UpdateOptions{})
	if err != nil {
		return nil, err
	}
	return mutationResult(updated, true, false), nil
}

func (mutator *KubernetesMutator) patchDeploymentConfig(
	ctx context.Context,
	deployment *appsv1.Deployment,
	parameters Parameters,
	record OperationRecord,
) (map[string]any, error) {
	key := text(parameters, "configKey")
	desired := fmt.Sprint(parameters["desiredValue"])
	values, err := deploymentConfigValues(deployment, key)
	if err != nil {
		return nil, err
	}
	operationHash := shortOperationHash(record.OperationID)
	if allValuesEqual(values, desired) && deployment.Annotations[operationHashAnnotation] == operationHash {
		return mutationResult(deployment, false, true), nil
	}
	if expected := text(parameters, "expectedCurrentValue"); expected != "" && !allValuesEqual(values, expected) {
		return nil, targetDrift("managed configuration changed before rollback")
	}
	if err := verifyGeneration(deployment, parameters); err != nil {
		return nil, err
	}
	next := deployment.DeepCopy()
	markDeployment(next, operationHash)
	for containerIndex := range next.Spec.Template.Spec.Containers {
		for envIndex := range next.Spec.Template.Spec.Containers[containerIndex].Env {
			variable := &next.Spec.Template.Spec.Containers[containerIndex].Env[envIndex]
			if variable.Name == key {
				variable.Value = desired
			}
		}
	}
	updated, err := mutator.client.AppsV1().Deployments(next.Namespace).Update(ctx, next, metav1.UpdateOptions{})
	if err != nil {
		return nil, err
	}
	return mutationResult(updated, true, false), nil
}

func (mutator *KubernetesMutator) revisionTemplate(
	ctx context.Context,
	deployment *appsv1.Deployment,
	revision string,
) (*corev1.PodTemplateSpec, error) {
	replicaSets, err := mutator.client.AppsV1().ReplicaSets(deployment.Namespace).List(
		ctx,
		metav1.ListOptions{LabelSelector: metav1.FormatLabelSelector(deployment.Spec.Selector)},
	)
	if err != nil {
		return nil, err
	}
	candidates := make([]appsv1.ReplicaSet, 0)
	for _, replicaSet := range replicaSets.Items {
		if replicaSet.Annotations[deploymentRevisionAnnotation] != revision {
			continue
		}
		for _, owner := range replicaSet.OwnerReferences {
			if owner.Controller != nil && *owner.Controller && owner.UID == deployment.UID {
				candidates = append(candidates, replicaSet)
				break
			}
		}
	}
	if len(candidates) == 0 {
		return nil, &APIError{
			Status:  http.StatusConflict,
			Code:    "REVISION_NOT_FOUND",
			Message: "requested deployment revision is unavailable",
		}
	}
	sort.Slice(candidates, func(left, right int) bool {
		return candidates[left].CreationTimestamp.After(candidates[right].CreationTimestamp.Time)
	})
	template := candidates[0].Spec.Template.DeepCopy()
	delete(template.Labels, podTemplateHashLabel)
	return template, nil
}

func verifyGeneration(deployment *appsv1.Deployment, parameters Parameters) error {
	expected := integer(parameters["expectedGeneration"])
	if boolean(parameters["rollback"]) {
		return nil
	}
	if deployment.Generation != expected {
		return targetDrift("workload generation changed after verification")
	}
	return nil
}

func verifyRollbackOperationGuard(
	deployment *appsv1.Deployment,
	parameters Parameters,
	record OperationRecord,
) error {
	if !boolean(parameters["rollback"]) {
		return nil
	}
	marker := deployment.Annotations[operationHashAnnotation]
	originalMarker := shortOperationHash(text(parameters, "rollbackOfOperationId"))
	rollbackMarker := shortOperationHash(record.OperationID)
	if marker != originalMarker && marker != rollbackMarker {
		return targetDrift("workload no longer carries the operation selected for rollback")
	}
	return nil
}

func verifyRollbackReplicaGuard(deployment *appsv1.Deployment, parameters Parameters) error {
	if !boolean(parameters["rollback"]) {
		return nil
	}
	expected, ok := exactInteger(parameters["expectedCurrentReplicas"])
	if !ok || int64(int32Value(deployment.Spec.Replicas)) != expected {
		return targetDrift("workload replica count changed before rollback")
	}
	return nil
}

func deploymentConfigValues(deployment *appsv1.Deployment, key string) ([]string, error) {
	values := make([]string, 0)
	for _, container := range deployment.Spec.Template.Spec.Containers {
		for _, variable := range container.Env {
			if variable.Name != key {
				continue
			}
			if variable.ValueFrom != nil {
				return nil, &APIError{
					Status:  http.StatusConflict,
					Code:    "CONFIG_VALUE_SOURCE_UNSUPPORTED",
					Message: "managed config cannot overwrite a valueFrom environment variable",
				}
			}
			values = append(values, variable.Value)
		}
	}
	if len(values) == 0 {
		return nil, &APIError{
			Status:  http.StatusConflict,
			Code:    "CONFIG_KEY_NOT_FOUND",
			Message: "managed config key is not present as a plain workload environment variable",
		}
	}
	return values, nil
}

func allValuesEqual(values []string, expected string) bool {
	if len(values) == 0 {
		return false
	}
	for _, value := range values {
		if value != expected {
			return false
		}
	}
	return true
}

func mutationResult(deployment *appsv1.Deployment, applied, reconciled bool) map[string]any {
	return map[string]any{
		"applied":         applied,
		"reconciled":      reconciled,
		"operationMarker": deployment.Annotations[operationHashAnnotation],
		"resource": map[string]any{
			"kind":            "Deployment",
			"namespace":       deployment.Namespace,
			"name":            deployment.Name,
			"uid":             string(deployment.UID),
			"resourceVersion": deployment.ResourceVersion,
			"generation":      deployment.Generation,
		},
	}
}

func markDeployment(deployment *appsv1.Deployment, operationHash string) {
	if deployment.Annotations == nil {
		deployment.Annotations = make(map[string]string)
	}
	deployment.Annotations[operationHashAnnotation] = operationHash
}

func mutationRequestHash(action string, parameters Parameters) (string, error) {
	encoded, err := json.Marshal(map[string]any{"action": action, "parameters": parameters})
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(encoded)
	return hex.EncodeToString(digest[:]), nil
}

func shortOperationHash(operationID string) string {
	digest := sha256.Sum256([]byte(operationID))
	return hex.EncodeToString(digest[:16])
}

func replayResult(result map[string]any) map[string]any {
	replayed := copyAnyMap(result)
	if replayed == nil {
		replayed = make(map[string]any)
	}
	replayed["replayed"] = true
	return replayed
}

func mutationAPIError(err error) *APIError {
	if apiError, ok := err.(*APIError); ok {
		return apiError
	}
	switch {
	case apierrors.IsForbidden(err):
		return &APIError{Status: 403, Code: "RBAC_FORBIDDEN", Message: "Kubernetes RBAC denied the mutation"}
	case apierrors.IsNotFound(err):
		return &APIError{Status: 404, Code: "RESOURCE_NOT_FOUND", Message: "mutation target was not found"}
	case apierrors.IsConflict(err):
		return &APIError{Status: 409, Code: "TARGET_CONFLICT", Message: "mutation target changed concurrently"}
	case apierrors.IsBadRequest(err), apierrors.IsInvalid(err):
		return &APIError{Status: 400, Code: "INVALID_KUBERNETES_REQUEST", Message: "Kubernetes rejected the mutation"}
	case apierrors.IsTimeout(err), apierrors.IsServerTimeout(err):
		return &APIError{Status: 504, Code: "KUBERNETES_TIMEOUT", Message: "Kubernetes mutation timed out"}
	default:
		return &APIError{Status: 502, Code: "KUBERNETES_UNAVAILABLE", Message: "Kubernetes mutation failed"}
	}
}

func ledgerAPIError(_ error) *APIError {
	return &APIError{
		Status:  http.StatusServiceUnavailable,
		Code:    "OPERATION_LEDGER_UNAVAILABLE",
		Message: "durable operation ledger is unavailable",
	}
}

func targetDrift(message string) *APIError {
	return &APIError{Status: http.StatusConflict, Code: "TARGET_DRIFT", Message: message}
}

func exactInteger(value any) (int64, bool) {
	switch typed := value.(type) {
	case int:
		return int64(typed), true
	case int32:
		return int64(typed), true
	case int64:
		return typed, true
	case float64:
		converted := int64(typed)
		return converted, float64(converted) == typed
	case json.Number:
		parsed, err := typed.Int64()
		return parsed, err == nil
	case string:
		parsed, err := strconv.ParseInt(strings.TrimSpace(typed), 10, 64)
		return parsed, err == nil
	default:
		return 0, false
	}
}

func integer(value any) int64 {
	parsed, _ := exactInteger(value)
	return parsed
}

func nonZero(value, fallback int) int {
	if value != 0 {
		return value
	}
	return fallback
}

func nonBlank(value, fallback string) string {
	if strings.TrimSpace(value) != "" {
		return value
	}
	return fallback
}
