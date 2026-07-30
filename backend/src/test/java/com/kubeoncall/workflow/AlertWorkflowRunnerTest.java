package com.kubeoncall.workflow;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

class AlertWorkflowRunnerTest {

    @Test
    void shouldUseDedicatedTimeoutOnlyForIntelligentDiagnosis() {
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        WorkflowNodeExecutor executor = mock(WorkflowNodeExecutor.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getWorkflow().setNodeTimeoutMillis(3000);
        properties.getWorkflow().setDiagnosisNodeTimeoutMillis(45000);
        AlertWorkflowDefinition stateCompare = definition("stateCompareNode");
        AlertWorkflowDefinition diagnosis = definition("intelligentDiagnosisNode");
        when(factory.buildWorkflow()).thenReturn(List.of(stateCompare, diagnosis));
        AlertWorkflowContext context = new AlertWorkflowContext(
                new AlarmEvent(
                        "alarm-1", "dedup-1", "prometheus", "P2", "node-a", "node pressure", Instant.now(), Map.of()),
                Instant.now());

        new AlertWorkflowRunner(factory, executor, properties)
                .run(context, AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "test"));

        verify(executor).execute(same(stateCompare), same(context), eq(Duration.ofMillis(3000)));
        verify(executor).execute(same(diagnosis), same(context), eq(Duration.ofMillis(45000)));
    }

    private AlertWorkflowDefinition definition(String name) {
        return new AlertWorkflowDefinition(
                name, true, List.of(), ignored -> new NodeResult(name, NodeStatus.SUCCESS, "ok", Map.of()));
    }
}
