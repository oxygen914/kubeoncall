package com.kubeoncall.agent.verifier;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VerifierApprovalNodeTest {

    @Test
    void shouldSkipApprovalCreationWhenDecisionIsAllow() {
        var approvalService = mock(com.kubeoncall.approval.ApprovalService.class);
        VerifierApprovalNode node = new VerifierApprovalNode(approvalService);
        GraphState state = new GraphState();
        state.getContext().put("verifierDecision", "ALLOW");

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("Approval not required", result.message());
        verify(approvalService, never()).createPending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRequireWaitingWhenDecisionNeedsApproval() {
        var approvalService = mock(com.kubeoncall.approval.ApprovalService.class);
        VerifierApprovalNode node = new VerifierApprovalNode(approvalService);
        GraphState state = new GraphState();
        state.setExecutionId("exec-1");
        state.getContext().put("verifierDecision", "APPROVAL_REQUIRED");
        state.getContext().put("verifierRiskReasons", List.of("Risk level requires human approval"));

        com.kubeoncall.domain.approval.ApprovalRequest request = new com.kubeoncall.domain.approval.ApprovalRequest(
                "exec-1", null, null, "system", com.kubeoncall.domain.approval.ApprovalDecision.PENDING,
                java.time.Instant.now(), null, "comment", null, false, List.of("Risk level requires human approval")
        );
        org.mockito.Mockito.when(approvalService.createPending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(request);

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.WAITING, result.status());
        assertEquals("Approval created", result.message());
        assertEquals("exec-1", result.payload().get("executionId"));
    }
}
