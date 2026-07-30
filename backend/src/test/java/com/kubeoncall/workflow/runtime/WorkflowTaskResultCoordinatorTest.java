package com.kubeoncall.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkflowTaskResultCoordinatorTest {

    @Test
    void finalizationResultIgnoresNullDetailEntries() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("planner", Map.of("mode", "REAL_MODEL"));
        details.put("plan", null);

        WorkflowTaskResultCoordinator.FinalizationResult result = new WorkflowTaskResultCoordinator.FinalizationResult(
                "exe_1", "FAILED", "diagnosis failed", null, true, null, details);

        assertThat(result.details())
                .containsEntry("planner", Map.of("mode", "REAL_MODEL"))
                .doesNotContainKey("plan");
    }
}
