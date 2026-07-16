package com.kubeoncall.alarm.correlation;

import java.util.List;

/** A scored, explainable relationship between an alarm and a recent change. */
public record ChangeCorrelation(
        ChangeEvent changeEvent, double correlationScore, String correlationReason, List<String> suggestions) {

    public ChangeCorrelation {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }
}
