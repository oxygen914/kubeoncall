package com.kubeoncall.approval;

import java.util.Optional;

import com.kubeoncall.domain.approval.ApprovalRequest;

public interface ApprovalRepository {

    void save(ApprovalRequest request);

    Optional<ApprovalRequest> findByExecutionId(String executionId);
}
