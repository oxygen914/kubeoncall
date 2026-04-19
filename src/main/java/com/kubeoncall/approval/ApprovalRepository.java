package com.kubeoncall.approval;

import com.kubeoncall.domain.approval.ApprovalRequest;

import java.util.Optional;

public interface ApprovalRepository {

    void save(ApprovalRequest request);

    Optional<ApprovalRequest> findByExecutionId(String executionId);
}
