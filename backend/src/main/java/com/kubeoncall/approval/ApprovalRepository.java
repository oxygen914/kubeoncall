package com.kubeoncall.approval;

import java.util.Optional;

import com.kubeoncall.domain.approval.ApprovalRequest;

public interface ApprovalRepository {

    void save(ApprovalRequest request);

    Optional<ApprovalRequest> findByExecutionId(String executionId);

    /**
     * Atomically replaces an approval only when its stored value still equals {@code expected}.
     * This prevents concurrent decision requests from both resuming the same execution.
     */
    boolean compareAndSet(ApprovalRequest expected, ApprovalRequest updated);
}
