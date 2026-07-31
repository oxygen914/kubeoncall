package com.kubeoncall.domain.task;

import java.time.Instant;
import java.util.List;

public record TaskPlan(
        String executionId, String userRequest, List<Task> tasks, Instant createdAt, boolean approvalRequired) {}
