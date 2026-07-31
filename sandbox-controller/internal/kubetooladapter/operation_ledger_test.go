package kubetooladapter

import (
	"context"
	"testing"
	"time"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
)

func TestOperationLedgerPrunesOnlyExpiredTerminalRecords(t *testing.T) {
	client := fake.NewSimpleClientset()
	ledger := NewKubernetesOperationLedger(client, "kubeoncall-operations")
	completed := OperationRecord{
		OperationID: "exec-1:task-1:kubernetes.scaleWorkload",
		RequestHash: "hash-1",
		Action:      "scaleWorkload",
		Status:      "PENDING",
		StartedAt:   time.Now().UTC(),
	}
	pending := OperationRecord{
		OperationID: "exec-2:task-2:kubernetes.rolloutRestart",
		RequestHash: "hash-2",
		Action:      "rolloutRestart",
		Status:      "PENDING",
		StartedAt:   time.Now().UTC(),
	}
	if _, _, err := ledger.Begin(context.Background(), completed); err != nil {
		t.Fatalf("begin completed record: %v", err)
	}
	if err := ledger.Complete(context.Background(), completed, map[string]any{"applied": true}); err != nil {
		t.Fatalf("complete record: %v", err)
	}
	if _, _, err := ledger.Begin(context.Background(), pending); err != nil {
		t.Fatalf("begin pending record: %v", err)
	}

	deleted, err := ledger.Prune(context.Background(), time.Now().UTC().Add(time.Hour))
	if err != nil {
		t.Fatalf("prune records: %v", err)
	}
	if deleted != 1 {
		t.Fatalf("expected one terminal record to be pruned, got %d", deleted)
	}
	if _, err := client.CoreV1().ConfigMaps("kubeoncall-operations").Get(
		context.Background(),
		operationRecordName(completed.OperationID),
		metav1.GetOptions{},
	); !apierrors.IsNotFound(err) {
		t.Fatalf("expected completed record to be deleted, got %v", err)
	}
	if _, err := client.CoreV1().ConfigMaps("kubeoncall-operations").Get(
		context.Background(),
		operationRecordName(pending.OperationID),
		metav1.GetOptions{},
	); err != nil {
		t.Fatalf("pending record must be retained: %v", err)
	}
}

func TestOperationLedgerDoesNotOverwriteSuccessfulRecordWithLateFailure(t *testing.T) {
	client := fake.NewSimpleClientset()
	ledger := NewKubernetesOperationLedger(client, "kubeoncall-operations")
	record := OperationRecord{
		OperationID: "exec-1:task-1:kubernetes.scaleWorkload",
		RequestHash: "hash-1",
		Action:      "scaleWorkload",
		Status:      "PENDING",
		StartedAt:   time.Now().UTC(),
	}
	if _, _, err := ledger.Begin(context.Background(), record); err != nil {
		t.Fatalf("begin record: %v", err)
	}
	if err := ledger.Complete(context.Background(), record, map[string]any{"applied": true}); err != nil {
		t.Fatalf("complete record: %v", err)
	}
	if err := ledger.Fail(
		context.Background(),
		record,
		&APIError{Status: 409, Code: "TARGET_CONFLICT", Message: "late conflict"},
	); err == nil {
		t.Fatal("expected late failure to be rejected")
	}
	configMap, err := client.CoreV1().ConfigMaps("kubeoncall-operations").Get(
		context.Background(),
		operationRecordName(record.OperationID),
		metav1.GetOptions{},
	)
	if err != nil {
		t.Fatalf("read record: %v", err)
	}
	stored, err := decodeOperationRecord(configMap)
	if err != nil {
		t.Fatalf("decode record: %v", err)
	}
	if stored.Status != "SUCCEEDED" || stored.Result["applied"] != true {
		t.Fatalf("successful terminal record was overwritten: %+v", stored)
	}
}
