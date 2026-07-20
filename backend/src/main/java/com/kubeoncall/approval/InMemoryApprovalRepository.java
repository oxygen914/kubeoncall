package com.kubeoncall.approval;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import com.kubeoncall.domain.approval.ApprovalRequest;

@Repository
@ConditionalOnProperty(prefix = "kubeoncall.approval", name = "repository", havingValue = "memory")
public class InMemoryApprovalRepository implements ApprovalRepository {

    private final Map<String, ApprovalRequest> store = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalRequest request) {
        store.put(request.executionId(), request);
    }

    @Override
    public Optional<ApprovalRequest> findByExecutionId(String executionId) {
        return Optional.ofNullable(store.get(executionId));
    }

    @Override
    public boolean compareAndSet(ApprovalRequest expected, ApprovalRequest updated) {
        if (expected == null || updated == null || expected.executionId() == null) {
            return false;
        }
        return store.replace(expected.executionId(), expected, updated);
    }
}
