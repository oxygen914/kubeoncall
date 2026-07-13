package com.kubeoncall.web.dto;

import java.util.List;

public record AlarmPolicyReplayResponse(
        String policyVersion, int total, int matched, int unmatched, List<AlarmPolicyEvaluationResponse> results) {
    public AlarmPolicyReplayResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
