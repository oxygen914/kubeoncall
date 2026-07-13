package com.kubeoncall.alarm.domain;

import java.util.List;

/**
 * The actions a matched policy prescribes. Crucially, {@code autoSilence} defaults to {@code false}
 * — silence is a change/maintenance action and must never be the default outcome of a P0/P1 alarm.
 */
public record AlarmAction(
        String workflowTemplate,
        String notificationChannel,
        boolean approvalRequiredForActions,
        boolean autoSilence,
        List<String> allowedTools) {

    public AlarmAction {
        if (allowedTools == null) {
            allowedTools = List.of();
        }
    }
}
