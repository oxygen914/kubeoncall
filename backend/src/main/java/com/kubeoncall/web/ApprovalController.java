package com.kubeoncall.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.service.AskService;
import com.kubeoncall.web.dto.ApprovalDecisionRequest;
import com.kubeoncall.web.dto.ApprovalDetailResponse;
import com.kubeoncall.web.dto.AskResponse;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final AskService askService;

    public ApprovalController(AskService askService) {
        this.askService = askService;
    }

    @GetMapping("/{executionId}")
    public ApprovalDetailResponse detail(@PathVariable String executionId) {
        AskService.ApprovalDetailResult result = askService.getApprovalDetail(executionId);
        return ApprovalDetailResponse.from(result.approvalRequest(), result.graphState());
    }

    @PostMapping("/{executionId}")
    public AskResponse decide(@PathVariable String executionId, @Valid @RequestBody ApprovalDecisionRequest request) {
        ApprovalDecision decision = ApprovalDecision.valueOf(request.decision().toUpperCase());
        AskService.ApprovalExecutionResult result =
                askService.decideAndResume(executionId, decision, request.comment(), request.decidedBy());
        return new AskResponse(
                result.executionId(), result.status(), result.message(), result.sessionId(), result.details());
    }
}
