package com.kubeoncall.web.dto;

import java.util.List;

public record AlarmPolicyReplayRequest(List<AlarmRequest> alarms) {
}
