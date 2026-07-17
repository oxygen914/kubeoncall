package com.kubeoncall.approval;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.task.TaskPlan;
import com.kubeoncall.state.GraphStateStore;

class ApprovalServiceTest {

    @Test
    void shouldRejectConcurrentApprovalDecision() {
        ApprovalRepository repository = mock(ApprovalRepository.class);
        GraphStateStore stateStore = mock(GraphStateStore.class);
        ApprovalRequest pending = request();
        GraphState state = new GraphState();
        state.setExecutionId("exec-1");
        state.setStatus(GraphStatus.PAUSED);
        when(repository.findByExecutionId("exec-1")).thenReturn(Optional.of(pending));
        when(stateStore.find("exec-1")).thenReturn(Optional.of(state));
        when(repository.compareAndSet(
                        org.mockito.ArgumentMatchers.eq(pending),
                        org.mockito.ArgumentMatchers.any(ApprovalRequest.class)))
                .thenReturn(false);
        ApprovalService service = new ApprovalService(repository, stateStore, new KubeOnCallProperties());

        assertThrows(
                IllegalStateException.class,
                () -> service.decide("exec-1", ApprovalDecision.APPROVED, "ok", "operator"));
        verify(stateStore, never()).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private ApprovalRequest request() {
        return new ApprovalRequest(
                "exec-1",
                new TaskPlan("exec-1", "q", List.of(), Instant.now(), true),
                "task-1",
                "requester",
                ApprovalDecision.PENDING,
                Instant.now(),
                null,
                null,
                null,
                false,
                List.of("risk"));
    }
}
