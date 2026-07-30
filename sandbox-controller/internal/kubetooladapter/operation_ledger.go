package kubetooladapter

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/util/retry"
)

const operationRecordKey = "operation.json"

type OperationRecord struct {
	OperationID  string         `json:"operationId"`
	RequestHash  string         `json:"requestHash"`
	Action       string         `json:"action"`
	Status       string         `json:"status"`
	StartedAt    time.Time      `json:"startedAt"`
	FinishedAt   *time.Time     `json:"finishedAt,omitempty"`
	Result       map[string]any `json:"result,omitempty"`
	ErrorStatus  int            `json:"errorStatus,omitempty"`
	ErrorCode    string         `json:"errorCode,omitempty"`
	ErrorMessage string         `json:"errorMessage,omitempty"`
}

type OperationLedger interface {
	Ready(context.Context) error
	Begin(context.Context, OperationRecord) (OperationRecord, bool, error)
	Complete(context.Context, OperationRecord, map[string]any) error
	Fail(context.Context, OperationRecord, *APIError) error
}

type KubernetesOperationLedger struct {
	client    kubernetes.Interface
	namespace string
}

func NewKubernetesOperationLedger(client kubernetes.Interface, namespace string) *KubernetesOperationLedger {
	return &KubernetesOperationLedger{client: client, namespace: namespace}
}

func (ledger *KubernetesOperationLedger) Ready(ctx context.Context) error {
	_, err := ledger.client.CoreV1().ConfigMaps(ledger.namespace).List(ctx, metav1.ListOptions{Limit: 1})
	return err
}

func (ledger *KubernetesOperationLedger) Begin(
	ctx context.Context,
	record OperationRecord,
) (OperationRecord, bool, error) {
	name := operationRecordName(record.OperationID)
	existing, err := ledger.client.CoreV1().ConfigMaps(ledger.namespace).Get(ctx, name, metav1.GetOptions{})
	if err == nil {
		decoded, decodeErr := decodeOperationRecord(existing)
		return decoded, false, decodeErr
	}
	if !apierrors.IsNotFound(err) {
		return OperationRecord{}, false, err
	}
	configMap, encodeErr := encodeOperationRecord(name, ledger.namespace, record)
	if encodeErr != nil {
		return OperationRecord{}, false, encodeErr
	}
	_, createErr := ledger.client.CoreV1().ConfigMaps(ledger.namespace).Create(ctx, configMap, metav1.CreateOptions{})
	if createErr == nil {
		return record, true, nil
	}
	if !apierrors.IsAlreadyExists(createErr) {
		return OperationRecord{}, false, createErr
	}
	existing, err = ledger.client.CoreV1().ConfigMaps(ledger.namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return OperationRecord{}, false, err
	}
	decoded, decodeErr := decodeOperationRecord(existing)
	return decoded, false, decodeErr
}

func (ledger *KubernetesOperationLedger) Complete(
	ctx context.Context,
	record OperationRecord,
	result map[string]any,
) error {
	return ledger.update(ctx, record, "SUCCEEDED", func(stored *OperationRecord) {
		now := time.Now().UTC()
		stored.Status = "SUCCEEDED"
		stored.FinishedAt = &now
		stored.Result = copyAnyMap(result)
		stored.ErrorStatus = 0
		stored.ErrorCode = ""
		stored.ErrorMessage = ""
	})
}

func (ledger *KubernetesOperationLedger) Fail(
	ctx context.Context,
	record OperationRecord,
	apiError *APIError,
) error {
	return ledger.update(ctx, record, "FAILED", func(stored *OperationRecord) {
		now := time.Now().UTC()
		stored.Status = "FAILED"
		stored.FinishedAt = &now
		stored.Result = nil
		stored.ErrorStatus = apiError.Status
		stored.ErrorCode = apiError.Code
		stored.ErrorMessage = apiError.Message
	})
}

// Prune removes only terminal records older than the retention cutoff. PENDING records represent
// an unknown external outcome and must remain available for explicit reconciliation.
func (ledger *KubernetesOperationLedger) Prune(ctx context.Context, cutoff time.Time) (int, error) {
	records, err := ledger.client.CoreV1().ConfigMaps(ledger.namespace).List(ctx, metav1.ListOptions{
		LabelSelector: "app.kubernetes.io/name=kubernetes-mutation-adapter,app.kubernetes.io/component=operation-ledger",
	})
	if err != nil {
		return 0, err
	}
	deleted := 0
	for index := range records.Items {
		configMap := &records.Items[index]
		record, decodeErr := decodeOperationRecord(configMap)
		if decodeErr != nil || record.Status == "PENDING" || record.FinishedAt == nil || !record.FinishedAt.Before(cutoff) {
			continue
		}
		if err := ledger.client.CoreV1().ConfigMaps(ledger.namespace).Delete(
			ctx,
			configMap.Name,
			metav1.DeleteOptions{Preconditions: &metav1.Preconditions{UID: &configMap.UID}},
		); err != nil && !apierrors.IsNotFound(err) {
			return deleted, err
		}
		deleted++
	}
	return deleted, nil
}

func (ledger *KubernetesOperationLedger) update(
	ctx context.Context,
	record OperationRecord,
	terminalStatus string,
	mutate func(*OperationRecord),
) error {
	name := operationRecordName(record.OperationID)
	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		configMap, err := ledger.client.CoreV1().ConfigMaps(ledger.namespace).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return err
		}
		stored, err := decodeOperationRecord(configMap)
		if err != nil {
			return err
		}
		if stored.OperationID != record.OperationID || stored.RequestHash != record.RequestHash {
			return fmt.Errorf("operation ledger identity mismatch")
		}
		if stored.Status == terminalStatus {
			return nil
		}
		if stored.Status != "PENDING" {
			return fmt.Errorf("operation ledger is already terminal with status %s", stored.Status)
		}
		mutate(&stored)
		encoded, err := json.Marshal(stored)
		if err != nil {
			return err
		}
		configMap.Data[operationRecordKey] = string(encoded)
		_, err = ledger.client.CoreV1().ConfigMaps(ledger.namespace).Update(ctx, configMap, metav1.UpdateOptions{})
		return err
	})
}

func encodeOperationRecord(name, namespace string, record OperationRecord) (*corev1.ConfigMap, error) {
	encoded, err := json.Marshal(record)
	if err != nil {
		return nil, err
	}
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: namespace,
			Labels: map[string]string{
				"app.kubernetes.io/name":      "kubernetes-mutation-adapter",
				"app.kubernetes.io/component": "operation-ledger",
				"ops.kubeoncall.io/action":    labelValue(record.Action),
			},
		},
		Immutable: boolPointer(false),
		Data:      map[string]string{operationRecordKey: string(encoded)},
	}, nil
}

func decodeOperationRecord(configMap *corev1.ConfigMap) (OperationRecord, error) {
	raw := configMap.Data[operationRecordKey]
	if raw == "" {
		return OperationRecord{}, fmt.Errorf("operation ledger record is empty")
	}
	var record OperationRecord
	if err := json.Unmarshal([]byte(raw), &record); err != nil {
		return OperationRecord{}, fmt.Errorf("operation ledger record is invalid: %w", err)
	}
	if record.OperationID == "" || record.RequestHash == "" || record.Status == "" {
		return OperationRecord{}, fmt.Errorf("operation ledger record is incomplete")
	}
	return record, nil
}

func operationRecordName(operationID string) string {
	digest := sha256.Sum256([]byte(operationID))
	return "koc-op-" + hex.EncodeToString(digest[:16])
}

func labelValue(value string) string {
	if len(value) <= 63 {
		return value
	}
	return value[:63]
}

func boolPointer(value bool) *bool {
	return &value
}

func copyAnyMap(value map[string]any) map[string]any {
	if value == nil {
		return nil
	}
	result := make(map[string]any, len(value))
	for key, item := range value {
		result[key] = item
	}
	return result
}
