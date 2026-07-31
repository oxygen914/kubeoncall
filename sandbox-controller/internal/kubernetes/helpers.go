package kubernetes

import (
	"errors"
	"regexp"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// Sentinel errors so callers can branch on lifecycle outcomes without parsing Kubernetes error text.
var (
	ErrInvalidRunID   = errors.New("invalid run id")
	ErrAlreadyExists  = errors.New("job already exists")
	ErrUnknownTool    = errors.New("unknown or unsafe tool")
	ErrInvalidRequest = errors.New("invalid job request")
)

var runIDRegexp = regexp.MustCompile(`^sbx_[0-9a-f]{1,128}$`)

func runIDRegex(runID string) bool {
	return runIDRegexp.MatchString(runID)
}

func validateRequest(request jobs.Request) error {
	if !runIDRegex(request.RunID) {
		return ErrInvalidRunID
	}
	if request.ToolID == "" || request.ToolVersion == "" || request.InputArtifactURI == "" {
		return ErrInvalidRequest
	}
	return nil
}

func isAlreadyExists(err error) bool {
	return apierrors.IsAlreadyExists(err)
}

func isNotFound(err error) bool {
	return apierrors.IsNotFound(err)
}

func ptrInt64(value int64) *int64 { return &value }

func ptrBool(value bool) *bool { return &value }

// milliCPU converts millicores to a Kubernetes quantity string (e.g. 500 -> "500m").
func milliCPU(milli int64) resource.Quantity {
	return *resource.NewMilliQuantity(milli, resource.DecimalSI)
}

// mebiBytes converts mebibytes to a Kubernetes binary-suffix quantity (e.g. 512 -> "512Mi").
func mebiBytes(mib int64) resource.Quantity {
	return *resource.NewQuantity(mib*1024*1024, resource.BinarySI)
}

// Ensure corev1 and metav1 are referenced even when helpers are trimmed by dead-code analysis.
var (
	_ corev1.ResourceList
	_ metav1.ObjectMeta
)
