package com.kubeoncall.web.dto;

import java.util.List;

import com.kubeoncall.alarm.suppression.AlarmSuppressionRule;

public record AlarmSuppressionRuleListResponse(String activeVersion, List<AlarmSuppressionRule> rules) {
    public AlarmSuppressionRuleListResponse {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
