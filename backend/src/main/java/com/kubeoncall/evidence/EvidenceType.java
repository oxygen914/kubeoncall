package com.kubeoncall.evidence;

public enum EvidenceType {
    RESOURCE_STATE,
    K8S_EVENT,
    POD_LOG,
    METRIC,
    ALERT,
    CHANGE_EVENT,
    SOP,
    OPERATION_RESULT,
    VERIFICATION_RESULT,
    ROLLBACK_RESULT
}
