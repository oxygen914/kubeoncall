package com.kubeoncall.web.dto;

public record AlarmPolicyEvaluationResponse(
        String alarmId,
        String fingerprint,
        String alertName,
        boolean matched,
        String policyId,
        String policyVersion,
        String severity,
        String workflowTemplate,
        String runbookId,
        String reason) {}
