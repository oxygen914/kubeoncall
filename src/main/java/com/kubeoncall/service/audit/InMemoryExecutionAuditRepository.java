package com.kubeoncall.service.audit;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(prefix = "kubeoncall.audit", name = "repository", havingValue = "memory")
public class InMemoryExecutionAuditRepository implements ExecutionAuditRepository {

    private final List<ExecutionAuditRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void save(ExecutionAuditRecord record) {
        records.add(record);
    }

    @Override
    public List<ExecutionAuditRecord> findAll() {
        return List.copyOf(records);
    }

    @Override
    public List<ExecutionAuditRecord> findRecent(int limit) {
        return records.stream()
                .sorted(Comparator.comparing(ExecutionAuditRecord::occurredAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public void deleteBefore(Instant threshold) {
        if (threshold == null) {
            return;
        }
        List<ExecutionAuditRecord> snapshot = new ArrayList<>(records);
        for (ExecutionAuditRecord record : snapshot) {
            if (record.occurredAt() != null && record.occurredAt().isBefore(threshold)) {
                records.remove(record);
            }
        }
    }
}
